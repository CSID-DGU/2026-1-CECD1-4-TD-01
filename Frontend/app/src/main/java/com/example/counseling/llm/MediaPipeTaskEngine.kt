package com.example.counseling.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaPipeTaskEngine(
    private val context: Context,
) : CounselingEngine {
    private var llmInference: LlmInference? = null
    private var modelAvailable = false

    override suspend fun initialize(): EngineStatus = withContext(Dispatchers.IO) {
        val file = selectedModelDir(context).listFiles()
            ?.firstOrNull { it.isFile && it.canRead() && it.extension.equals("task", ignoreCase = true) }
            ?: return@withContext EngineStatus(false, "Select a .task model to test Gemma on device.")

        loadTaskModel(file)
    }

    suspend fun importAndLoadModel(uri: Uri): EngineStatus = withContext(Dispatchers.IO) {
        val imported = copyUriToModelDir(context, uri)
        loadTaskModel(imported)
    }

    private fun loadTaskModel(modelFile: File): EngineStatus {
        release()

        return runCatching {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTopK(64)
                .setMaxTokens(512)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            modelAvailable = true
            EngineStatus(true, "Loaded .task: ${modelFile.name}")
        }.getOrElse { error ->
            modelAvailable = false
            EngineStatus(false, ".task load failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override suspend fun generate(messages: List<ChatMessage>): String = withContext(Dispatchers.Default) {
        val inference = llmInference ?: return@withContext "Load a .task model first."
        val lastUserMessage = messages.lastOrNull { it.role == ChatRole.User }?.content.orEmpty()
        if (!modelAvailable || lastUserMessage.isBlank()) {
            return@withContext "Type a prompt after loading a .task model."
        }

        runCatching { inference.generateResponse(lastUserMessage) }
            .getOrElse { ".task inference failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    override fun release() {
        llmInference?.close()
        llmInference = null
        modelAvailable = false
    }

    private fun copyUriToModelDir(context: Context, uri: Uri): File {
        val fileName = queryDisplayName(context, uri)
            ?.takeIf { it.endsWith(".task", ignoreCase = true) }
            ?: "selected-model.task"
        val output = File(selectedModelDir(context), fileName)

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Selected model file could not be opened." }
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
