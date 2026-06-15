# LiteRT / AI Edge Gallery Migration Plan

## Why Google AI Edge Gallery Runs Better

Google AI Edge Gallery is not running ordinary GGUF through llama.cpp. Its fast path is based on Google AI Edge's LiteRT / LiteRT-LM stack.

The practical differences are:

- Runtime: LiteRT-LM instead of llama.cpp.
- Model format: `.litertlm` / task-bundle style models instead of `.gguf`.
- Mobile optimization: conversion and quantization are done for edge runtimes.
- Hardware backend options: CPU, GPU, and NPU backends are supported by LiteRT-LM.
- LLM runtime features: LiteRT-LM handles KV cache, stateful conversations, prompt caching/scoring, and session management.
- Gemma 3n / Gemma 4 support: official LiteRT community models exist, and Gemma 4 support includes MTP/speculative decoding paths in LiteRT-LM.

So the target architecture should be:

```text
Android app
  -> LiteRT-LM Kotlin API
  -> .litertlm model file
  -> Backend.CPU / Backend.GPU / Backend.NPU where supported
```

not:

```text
Android app
  -> llama.cpp JNI
  -> GGUF
```

The current GGUF path can stay as a fallback, but it is not the same runtime as Edge Gallery.

## Can We Convert From Safetensors?

Usually yes, but not from a single `.safetensors` file alone.

The converter needs a Hugging Face style model directory or repo, not only raw weights. Prepare one of these:

### Best Input

A merged Hugging Face model directory:

```text
model/
  config.json
  generation_config.json
  tokenizer.json or tokenizer.model
  tokenizer_config.json
  special_tokens_map.json
  model.safetensors or model-00001-of-000xx.safetensors ...
  model.safetensors.index.json if sharded
```

### If Fine-Tuning Was LoRA

Provide both:

```text
base model id or base model files
adapter_config.json
adapter_model.safetensors
tokenizer files if changed
```

Then merge LoRA into the base model first and convert the merged HF model.

### Not Enough By Itself

These are not enough alone:

```text
*.gguf
single model.safetensors with no config/tokenizer
LoRA adapter without base model
training checkpoint without tokenizer/config
```

GGUF is already a llama.cpp deployment artifact. Do not use it as the source for LiteRT conversion if the original HF/safetensors checkpoint is available.

## Conversion Server Requirements

Use a Linux GPU server if possible.

Minimum practical setup:

```text
Linux
Python 3.10+
Enough disk for original HF model + converted outputs
Large system RAM, preferably 64GB+
CUDA GPU helpful for loading/merging, though some LiteRT export paths are CPU-oriented
```

Install candidates:

```bash
python -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install litert-torch litert-lm-api torch transformers accelerate safetensors sentencepiece
```

If stable conversion fails for Gemma 4, try nightly packages:

```bash
pip install --pre litert-torch-nightly
```

## Expected Conversion Direction

The likely path is:

```text
HF safetensors checkpoint
  -> optional LoRA merge
  -> LiteRT Torch Generative export
  -> .litertlm
  -> Android LiteRT-LM Kotlin API
```

The exact command depends on the model architecture support in `litert_torch.generative.export_hf`.

A starting point to verify the toolchain is:

```bash
python -m litert_torch.generative.export_hf --help
```

Then test first with an official LiteRT community model before converting the custom counselor model:

```text
litert-community/gemma-4-E4B-it-litert-lm
litert-community/gemma-4-E2B-it-litert-lm
google/gemma-3n-E2B-it-litert-lm
```

## Android App Migration Steps

1. Keep current llama.cpp/GGUF engine behind an interface.
2. Add a second engine implementation: `LiteRtCounselingEngine`.
3. Add Gradle dependency:

```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:<version>")
```

4. Add optional native libraries to `AndroidManifest.xml` for GPU backend:

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

5. Change model picker to accept:

```text
*.litertlm
*.gguf
```

6. Route by extension:

```text
.litertlm -> LiteRT-LM
.gguf    -> llama.cpp fallback
```

7. For LiteRT-LM, initialize engine in a background coroutine:

```kotlin
val config = EngineConfig(
    modelPath = modelPath,
    backend = Backend.CPU(), // later try Backend.GPU()
    cacheDir = context.cacheDir.path,
)
val engine = Engine(config)
engine.initialize()
val conversation = engine.createConversation(...)
conversation.sendMessageAsync(userText).collect { ... }
```

8. Once `.litertlm` conversion succeeds, make LiteRT-LM the default path.

## What To Provide For Conversion

Send one of these packages:

### Preferred

The full merged HF model folder after fine-tuning:

```text
config.json
tokenizer files
safetensors shards
safetensors index
generation_config.json
```

### Acceptable

Base model name plus LoRA adapter:

```text
base_model: e.g. google/gemma-...
adapter_config.json
adapter_model.safetensors
training tokenizer/config if modified
```

### Also Useful

```text
training script or fine-tuning config
max sequence length used during training
chat template used during training
target quantization preference: int4 / int8
```

## Risk Notes

- Gemma 4 LiteRT conversion is newer than GGUF conversion. Some custom fine-tuned checkpoints may need toolchain fixes or nightly packages.
- If the model was fine-tuned from a base architecture not supported by LiteRT Torch Generative API, conversion may fail even if GGUF conversion worked.
- If conversion fails, the fallback is to use an official LiteRT Gemma E2B/E4B and apply supported LoRA if available, or retrain/fine-tune on a LiteRT-supported base.
