package com.example.counseling.llm

enum class ChatRole {
    User,
    Assistant,
    System,
}

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val attachmentLabel: String? = null,
)
