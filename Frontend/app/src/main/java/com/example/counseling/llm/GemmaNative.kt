package com.example.counseling.llm

object GemmaNative {
    init {
        try {
            System.loadLibrary("gemma_jni")
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                """
                NATIVE_LIBRARY_LOAD_FAILED
                libgemma_jni.so를 로드하지 못했습니다.
                확인할 것:
                1. 실제 기기가 arm64-v8a인지
                2. NDK/CMake 빌드가 성공했는지
                3. app/src/main/cpp/CMakeLists.txt가 올바른지
                원인: ${e.message}
                """.trimIndent(),
                e,
            )
        }
    }

    external fun loadModel(
        modelPath: String,
        contextSize: Int,
        threads: Int,
    ): String

    external fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
    ): String

    external fun releaseModel(): String
}
