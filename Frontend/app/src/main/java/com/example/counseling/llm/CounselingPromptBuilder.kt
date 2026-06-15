package com.example.counseling.llm

object CounselingPromptBuilder {
    fun build(messages: List<ChatMessage>): String {
        val systemPrompt = """
            당신은 한국어 상담 챗봇입니다.
            사용자의 말을 먼저 짧게 공감하고, 지금 느끼는 감정과 상황을 정리해 줍니다.
            답변은 질문-답변 흐름이 이어지도록 마지막에 사용자가 부담 없이 답할 수 있는 짧은 질문 하나를 포함합니다.
            판단하거나 훈계하지 않고, 단정적인 진단이나 처방을 하지 않습니다.
            자해나 타해 위험이 보이면 혼자 버티지 말고 즉시 주변 사람, 지역 긴급전화, 응급실, 전문기관에 도움을 요청하라고 안내합니다.
        """.trimIndent()

        return buildString {
            appendLine("<|turn>system")
            appendLine(systemPrompt)
            appendLine("<turn|>")

            messages.takeLast(4).forEach { message ->
                val role = when (message.role) {
                    ChatRole.User -> "user"
                    ChatRole.Assistant -> "model"
                    ChatRole.System -> "system"
                }
                appendLine("<|turn>$role")
                appendLine(message.content)
                appendLine("<turn|>")
            }

            appendLine("<|turn>model")
            appendLine("<|channel>thought")
            appendLine("<channel|>")
        }
    }
}
