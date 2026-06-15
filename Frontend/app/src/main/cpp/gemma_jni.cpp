#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

#include "common.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "CounselingGemma"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr int kBatchSize = 64;
constexpr int kOverflowHeadroom = 8;

std::mutex g_mutex;
bool g_backend_initialized = false;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
llama_batch g_batch{};
bool g_batch_initialized = false;

jstring to_jstring(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

std::string from_jstring(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? "" : chars;
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void release_locked() {
    if (g_batch_initialized) {
        llama_batch_free(g_batch);
        g_batch = {};
        g_batch_initialized = false;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

int decode_tokens(const llama_tokens &tokens, int &position) {
    for (int offset = 0; offset < static_cast<int>(tokens.size()); offset += kBatchSize) {
        const int current_batch_size = std::min(kBatchSize, static_cast<int>(tokens.size()) - offset);
        common_batch_clear(g_batch);

        for (int i = 0; i < current_batch_size; ++i) {
            const bool want_logits = offset + i == static_cast<int>(tokens.size()) - 1;
            common_batch_add(g_batch, tokens[offset + i], position + i, {0}, want_logits);
        }

        const int decode_result = llama_decode(g_context, g_batch);
        if (decode_result != 0) {
            LOGE("llama_decode prompt failed: %d", decode_result);
            return decode_result;
        }

        position += current_batch_size;
    }
    return 0;
}

bool is_valid_utf8(const char *string) {
    if (string == nullptr) {
        return true;
    }

    const auto *bytes = reinterpret_cast<const unsigned char *>(string);
    while (*bytes != 0x00) {
        int num;
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_counseling_llm_GemmaNative_loadModel(
        JNIEnv *env,
        jobject,
        jstring model_path,
        jint context_size,
        jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string path = from_jstring(env, model_path);

    if (path.empty()) {
        return to_jstring(env, "MODEL_PATH_EMPTY");
    }

    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama backend initialized");
    }

    release_locked();

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    model_params.use_direct_io = false;
    model_params.use_mlock = false;
    model_params.use_extra_bufts = false;
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) {
        return to_jstring(env, "MODEL_LOAD_FAILED:" + path);
    }

    const int trained_context = llama_model_n_ctx_train(g_model);
    const int requested_context = std::max(512, static_cast<int>(context_size));
    const int n_ctx = std::min(requested_context, trained_context);
    const int n_threads = std::max(1, static_cast<int>(threads));

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = kBatchSize;
    ctx_params.n_ubatch = kBatchSize;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.type_k = GGML_TYPE_Q8_0;
    ctx_params.type_v = GGML_TYPE_Q8_0;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    ctx_params.offload_kqv = false;
    ctx_params.op_offload = false;
    ctx_params.swa_full = false;
    ctx_params.kv_unified = false;

    g_context = llama_init_from_model(g_model, ctx_params);
    if (g_context == nullptr) {
        release_locked();
        return to_jstring(env, "CONTEXT_INIT_FAILED");
    }

    g_batch = llama_batch_init(kBatchSize, 0, 1);
    g_batch_initialized = true;

    char model_desc[256];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));
    std::ostringstream result;
    result << "MODEL_READY:" << model_desc << ", ctx=" << n_ctx << ", threads=" << n_threads;
    return to_jstring(env, result.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_counseling_llm_GemmaNative_generate(
        JNIEnv *env,
        jobject,
        jstring prompt,
        jint max_tokens,
        jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_model == nullptr || g_context == nullptr || !g_batch_initialized) {
        return to_jstring(env, "MODEL_NOT_LOADED");
    }

    const std::string prompt_text = from_jstring(env, prompt);
    if (prompt_text.empty()) {
        return to_jstring(env, "");
    }

    llama_memory_clear(llama_get_memory(g_context), false);

    int position = 0;
    llama_tokens prompt_tokens = common_tokenize(g_context, prompt_text, true, true);
    const int max_prompt_tokens = static_cast<int>(llama_n_ctx(g_context)) - kOverflowHeadroom - 1;
    if (static_cast<int>(prompt_tokens.size()) > max_prompt_tokens) {
        prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.end() - max_prompt_tokens);
    }

    if (decode_tokens(prompt_tokens, position) != 0) {
        return to_jstring(env, "PROMPT_DECODE_FAILED");
    }

    common_params_sampling sampling_params;
    sampling_params.temp = std::max(0.0f, static_cast<float>(temperature));
    common_sampler *sampler = common_sampler_init(g_model, sampling_params);
    if (sampler == nullptr) {
        return to_jstring(env, "SAMPLER_INIT_FAILED");
    }

    std::string output;
    std::string pending_utf8;
    const int limit = std::max(1, static_cast<int>(max_tokens));
    const auto *vocab = llama_model_get_vocab(g_model);

    for (int i = 0; i < limit; ++i) {
        if (position >= static_cast<int>(llama_n_ctx(g_context)) - kOverflowHeadroom) {
            break;
        }

        const llama_token token = common_sampler_sample(sampler, g_context, -1);
        common_sampler_accept(sampler, token, true);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        pending_utf8 += common_token_to_piece(g_context, token);
        if (is_valid_utf8(pending_utf8.c_str())) {
            output += pending_utf8;
            pending_utf8.clear();
        }

        common_batch_clear(g_batch);
        common_batch_add(g_batch, token, position, {0}, true);
        const int decode_result = llama_decode(g_context, g_batch);
        if (decode_result != 0) {
            LOGE("llama_decode generation failed: %d", decode_result);
            break;
        }
        position++;
    }

    common_sampler_free(sampler);
    return to_jstring(env, output);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_counseling_llm_GemmaNative_releaseModel(
        JNIEnv *env,
        jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
    return to_jstring(env, "MODEL_RELEASED");
}
