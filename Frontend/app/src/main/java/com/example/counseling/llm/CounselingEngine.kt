package com.example.counseling.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface CounselingEngine {
    suspend fun initialize(): EngineStatus
    suspend fun generate(messages: List<ChatMessage>): String
    fun release()
}

data class EngineStatus(
    val isModelLoaded: Boolean,
    val message: String,
)

class NativeCounselingEngine(
    private val context: Context,
    private val defaultModelFileName: String = "gemma4-e4b-counselor-q4_k_m.gguf",
) : CounselingEngine {
    private var initialized = false
    private var modelAvailable = false
    private var selectedModelFile: File? = null

    override suspend fun initialize(): EngineStatus = withContext(Dispatchers.IO) {
        loadModelFile(
            ModelFileProvider.resolveModelFile(
                context = context,
                modelFileName = defaultModelFileName,
            ),
        )
    }

    suspend fun importAndLoadModel(uri: Uri): EngineStatus = withContext(Dispatchers.IO) {
        val imported = ModelFileProvider.copyUriToModelDir(context, uri)
        selectedModelFile = imported
        loadModelFile(imported)
    }

    private fun loadModelFile(modelFile: File?): EngineStatus {
        if (modelFile == null) {
            initialized = true
            modelAvailable = false
            return EngineStatus(
                isModelLoaded = false,
                message = "모델 파일을 선택해 주세요.",
            )
        }

        release()

        val result = GemmaNative.loadModel(
            modelPath = modelFile.absolutePath,
            contextSize = 512,
            threads = 4,
        )

        initialized = true
        modelAvailable = result.startsWith("MODEL_READY")
        return EngineStatus(
            isModelLoaded = modelAvailable,
            message = if (modelAvailable) {
                "로드됨: ${modelFile.name}"
            } else {
                "$result (${modelFile.name})"
            },
        )
    }

    override suspend fun generate(messages: List<ChatMessage>): String = withContext(Dispatchers.Default) {
        if (!initialized) {
            initialize()
        }

        if (!modelAvailable) {
            return@withContext "아직 모델이 로드되지 않았습니다. 상단의 모델 선택 버튼으로 GGUF 파일을 선택해 주세요."
        }

        GemmaNative.generate(
            prompt = CounselingPromptBuilder.build(messages),
            maxTokens = 128,
            temperature = 0.7f,
        )
    }

    override fun release() {
        if (initialized && modelAvailable) {
            GemmaNative.releaseModel()
        }
        modelAvailable = false
    }
}

private object ModelFileProvider {
    fun resolveModelFile(
        context: Context,
        modelFileName: String,
    ): File? {
        selectedModelDir(context).listFiles()
            ?.firstOrNull { it.isFile && it.canRead() && it.extension.equals("gguf", ignoreCase = true) }
            ?.let { return it }

        val externalModel = File(selectedModelDir(context), modelFileName)
        if (externalModel.exists() && externalModel.isFile && externalModel.canRead()) {
            return externalModel
        }

        return null
    }

    fun copyUriToModelDir(context: Context, uri: Uri): File {
        val fileName = queryDisplayName(context, uri)
            ?.takeIf { it.endsWith(".gguf", ignoreCase = true) }
            ?: "selected-model.gguf"
        val output = File(selectedModelDir(context), fileName)

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "선택한 모델 파일을 열 수 없습니다." }
            output.outputStream().use { outputStream ->
                input.copyTo(outputStream, bufferSize = 1024 * 1024)
            }
        }

        return output
    }

    private fun selectedModelDir(context: Context): File {
        return File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }
}
