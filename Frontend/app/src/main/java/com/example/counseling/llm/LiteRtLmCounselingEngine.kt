package com.example.counseling.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtLmCounselingEngine(
    private val context: Context,
    private val defaultModelFileName: String = "gemma-4-E4B-it.litertlm",
) : CounselingEngine {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelAvailable = false
    private var systemInstruction: String = defaultSystemInstruction
    private var hasSentInstructionPrompt = false
    private var directAttachmentMode = false

    fun setDirectAttachmentMode(enabled: Boolean) {
        directAttachmentMode = enabled
    }

    override suspend fun initialize(): EngineStatus = withContext(Dispatchers.IO) {
        val model = resolveModelFile(context, defaultModelFileName)
            ?: return@withContext EngineStatus(false, "모델 로드를 눌러 Gemma 4 E4B IT .litertlm 파일을 선택해 주세요.")

        loadModel(model)
    }

    suspend fun importAndLoadModel(uri: Uri, systemPrompt: String): EngineStatus = withContext(Dispatchers.IO) {
        systemInstruction = systemPrompt.ifBlank { defaultSystemInstruction }
        loadModel(copyUriToModelDir(context, uri))
    }

    suspend fun updateSystemInstruction(systemPrompt: String): EngineStatus = withContext(Dispatchers.IO) {
        systemInstruction = systemPrompt.ifBlank { defaultSystemInstruction }
        val loadedEngine = engine ?: return@withContext EngineStatus(modelAvailable, "시스템 프롬프트를 저장했습니다. 다음 모델 로드부터 적용됩니다.")
        conversation?.close()
        conversation = createConversation(loadedEngine)
        hasSentInstructionPrompt = false
        EngineStatus(true, "시스템 프롬프트를 적용했습니다.")
    }

    private fun loadModel(modelFile: File): EngineStatus {
        release()

        return runCatching {
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                audioBackend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
            )
            val loadedEngine = Engine(engineConfig)
            loadedEngine.initialize()
            engine = loadedEngine
            conversation = createConversation(loadedEngine)
            modelAvailable = true
            EngineStatus(true, "LiteRT-LM 모델 로드 완료: ${modelFile.name}")
        }.getOrElse { error ->
            release()
            EngineStatus(false, "LiteRT-LM 모델 로드 실패: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun createConversation(loadedEngine: Engine): Conversation {
        return loadedEngine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.7,
                ),
            ),
        )
    }

    override suspend fun generate(messages: List<ChatMessage>): String = withContext(Dispatchers.Default) {
        generateStreaming(messages) { }
    }

    suspend fun generateStreaming(
        messages: List<ChatMessage>,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val chat = conversation ?: return@withContext "먼저 .litertlm 모델을 로드해 주세요."
        val lastUserMessage = messages.lastOrNull { it.role == ChatRole.User }
            ?: return@withContext "메시지를 입력해 주세요."
        if (!modelAvailable) {
            return@withContext "모델 로드 후 메시지를 입력해 주세요."
        }

        runCatching {
            val reply = sendMessage(
                chat,
                lastUserMessage,
                includeInstruction = !hasSentInstructionPrompt,
                onPartial = onPartial,
            )
            if (isSuspiciouslyShortReply(reply)) {
                retryAfterShortReply(lastUserMessage, onPartial)
            } else {
                reply
            }
        }.onSuccess {
            hasSentInstructionPrompt = true
        }.getOrElse { error ->
            if (lastUserMessage.imagePath != null || lastUserMessage.audioPath != null) {
                runCatching {
                    val fallbackMessage = lastUserMessage.copy(
                        content = """
                            ${lastUserMessage.content}

                            첨부 파일을 모델이 직접 처리하지 못했습니다. 첨부 내용은 확인하지 못했으므로, 사용자가 텍스트로 설명한 내용에만 근거해 답하세요.
                        """.trimIndent(),
                        imagePath = null,
                        audioPath = null,
                        attachmentLabel = null,
                    )
                    sendMessage(chat, fallbackMessage, includeInstruction = !hasSentInstructionPrompt, onPartial = onPartial)
                }.onSuccess {
                    hasSentInstructionPrompt = true
                }.getOrElse {
                    "LiteRT-LM 실행 실패: ${error.message ?: error.javaClass.simpleName}"
                }
            } else {
                "LiteRT-LM 실행 실패: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    override fun release() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        modelAvailable = false
    }

    private fun ChatMessage.toContents(includeInstruction: Boolean): Contents {
        val attachmentNotice = when {
            imagePath != null -> "\n\n[첨부 안내] 현재 상담 호출에서는 이미지 입력을 사용하지 않습니다. 사용자가 텍스트로 설명한 내용만 근거로 답하세요."
            audioPath != null && !directAttachmentMode -> "\n\n[첨부 안전 모드] 음성 파일이 첨부되었지만, 오디오 직접 분석이 꺼져 있어 파일을 직접 모델에 넘기지 않았습니다. 사용자가 텍스트로 설명한 내용만 근거로 답하세요."
            audioPath != null -> "\n\n[음성 입력 안내] 이 메시지에는 사용자가 직접 말한 음성 녹음이 첨부되어 있습니다. 첨부된 오디오를 사용자 발화로 간주하고, 들은 내용에 근거해 답하세요. 잘 들리지 않거나 확실하지 않으면 추측하지 말고 확인 질문을 하세요."
            else -> ""
        }
        val safeContent = content + attachmentNotice
        val textContent = if (includeInstruction) {
            buildGemmaInitialUserPrompt(systemInstruction, safeContent)
        } else {
            safeContent
        }
        val contents = buildList {
            if (textContent.isNotBlank()) add(Content.Text(textContent))
            if (directAttachmentMode && imagePath == null) {
                audioPath?.let { add(Content.AudioFile(it)) }
            }
        }
        return if (contents.isEmpty()) Contents.of("") else Contents.of(contents)
    }

    private suspend fun sendMessage(
        chat: Conversation,
        message: ChatMessage,
        includeInstruction: Boolean,
        onPartial: (String) -> Unit,
    ): String {
        val prompt = message.toContents(includeInstruction = includeInstruction)
        var latest = ""
        chat.sendMessageAsync(prompt).collect { response ->
            val text = response.textContent()
            latest = if (text.startsWith(latest)) text else latest + text
            onPartial(latest)
        }
        return latest.ifBlank { chat.sendMessage(prompt).textContent() }
    }

    private suspend fun retryAfterShortReply(
        message: ChatMessage,
        onPartial: (String) -> Unit,
    ): String {
        val loadedEngine = engine ?: return ""
        conversation?.close()
        val retryConversation = createConversation(loadedEngine)
        conversation = retryConversation
        hasSentInstructionPrompt = false
        onPartial("")
        val retryMessage = message.copy(
            content = """
                ${message.content}

                [Retry instruction]
                The previous response ended too early. Answer in Korean with at least one complete sentence.
                Briefly reflect the user's feeling or situation, then ask at most one useful follow-up question if needed.
                Do not output XML tags, hidden reasoning, or a single-symbol answer.
            """.trimIndent(),
        )
        return sendMessage(
            retryConversation,
            retryMessage,
            includeInstruction = true,
            onPartial = onPartial,
        )
    }

    private fun isSuspiciouslyShortReply(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val withoutTags = trimmed
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?is)<thinking>.*?</thinking>"), "")
            .replace(Regex("(?is)</?think(?:ing)?>"), "")
            .trim()
        return withoutTags.length <= 2 || withoutTags in setOf("<", ">", "...", ".", ",")
    }

    private fun buildGemmaInitialUserPrompt(instruction: String, userMessage: String): String {
        return """
            다음 지침을 이 대화 전체에 적용하세요.

            [상담 보조자 지침]
            ${instruction.trim()}

            [사용자 메시지]
            ${userMessage.trim()}
        """.trimIndent()
    }

    private fun resolveModelFile(context: Context, modelFileName: String): File? {
        selectedModelDir(context).listFiles()
            ?.filter { it.isFile && it.canRead() && it.extension.equals("litertlm", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?.let { return it }

        return File(selectedModelDir(context), modelFileName)
            .takeIf { it.exists() && it.isFile && it.canRead() }
    }

    private fun copyUriToModelDir(context: Context, uri: Uri): File {
        val fileName = queryDisplayName(context, uri)
            ?.takeIf { it.endsWith(".litertlm", ignoreCase = true) }
            ?: defaultModelFileName
        val output = File(selectedModelDir(context), fileName)
        val partial = File(selectedModelDir(context), "$fileName.partial")

        if (partial.exists()) partial.delete()

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "선택한 LiteRT-LM 모델 파일을 열 수 없습니다." }
            partial.outputStream().use { outputStream ->
                input.copyTo(outputStream, bufferSize = 1024 * 1024)
            }
        }

        if (partial.length() < 1024L * 1024L * 100L) {
            partial.delete()
            error("모델 파일이 너무 작습니다. Gemma 4 E4B IT .litertlm 파일을 다시 선택해 주세요.")
        }

        if (output.exists()) output.delete()
        check(partial.renameTo(output)) { "모델 파일 복사를 완료하지 못했습니다." }

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

    private fun com.google.ai.edge.litertlm.Message.textContent(): String {
        return contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
            .ifBlank { toString() }
    }

    companion object {
        val defaultSystemInstruction = """
            당신은 한국어로 응답하는 상담 보조자입니다. 진단이나 치료를 대신하지 않고, 사용자가 스스로 상황을 정리하고 다음 행동을 고를 수 있도록 돕습니다.

            상담 흐름:
            1. 먼저 사용자의 감정과 상황을 짧게 반영하고, 판단하거나 단정하지 않습니다.
            2. 필요한 경우 한 번에 하나씩 명확한 질문을 합니다.
            3. 사용자의 말에서 사실, 감정, 욕구, 위험 신호를 구분합니다.
            4. 가능한 선택지를 작고 실행 가능한 단계로 제안합니다.
            5. 개인정보, 의료, 법률, 재정 문제는 전문 기관 상담을 권합니다.

            위기 대응:
            사용자가 자살, 자해, 타해, 학대, 폭력, 긴급한 의료 위험, 극심한 절망감, 구체적인 계획이나 수단을 언급하면 일반 상담보다 안전 확인을 우선합니다.
            즉시 위험이 있거나 혼자 안전을 유지하기 어렵다면 지금 바로 112 또는 119에 연락하거나 가까운 응급실로 가도록 안내합니다.
            한국에서는 자살예방상담전화 109, 정신건강상담전화 1577-0199를 안내합니다.
            미국에 있는 사용자라면 988 Suicide & Crisis Lifeline을 안내합니다.
            위기 상황에서는 사용자가 믿을 수 있는 사람 곁으로 이동하고, 위험한 물건이나 수단에서 거리를 두도록 권합니다.

            응답 스타일:
            따뜻하지만 과장하지 않고, 간결하게 답합니다.
            필요할 때 **굵게** 강조를 사용합니다.
            사용자의 이미지를 받으면 관찰 가능한 내용만 조심스럽게 설명하고, 확실하지 않은 추정은 피합니다.
            음성이나 오디오 입력이 들어오면 들은 내용에 근거해 답하되, 불확실하면 확인 질문을 합니다.

            마음챙김과 안정화:
            사용자가 불안, 압박감, 긴장, 감정 과부하를 말하면 진단이나 치료처럼 말하지 말고, 현재 순간에 주의를 돌리는 짧은 연습을 선택지로 제안합니다.
            예시는 느린 호흡, 몸 감각 알아차리기, 5-4-3-2-1 감각 그라운딩, 짧은 산책, 점진적 근육 이완, 잠시 물 마시기처럼 안전하고 부담이 낮은 방법으로 제한합니다.
            마음챙김은 감정을 없애는 방법이 아니라 지금의 생각, 감정, 몸 감각을 판단하지 않고 알아차리는 연습이라고 설명합니다.
            사용자가 트라우마, 공황, 해리, 극심한 불안, 자해 충동을 말하면 눈을 감거나 내면에 오래 머무는 연습을 강요하지 말고, 주변 사물 이름 대기, 발바닥 감각 느끼기, 신뢰할 수 있는 사람에게 연락하기처럼 외부 지향적인 안정화부터 제안합니다.
            증상이 지속되거나 일상 기능을 크게 방해하면 정신건강 전문가나 의료기관 상담을 권합니다.
        """.trimIndent()
    }
}
