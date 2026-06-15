package com.example.counseling

import android.content.Context
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedChatSession(
    val id: String = ChatMemoryStore.DEFAULT_SESSION_ID,
    val title: String = "",
    val updatedAt: Long = 0L,
    val messages: List<ChatMessage>,
    val systemPrompt: String,
    val importantMemories: List<String>,
)

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

class ChatSessionStore(private val context: Context) {
    private val sessionFile: File
        get() = File(context.filesDir, "last_chat_session.json")
    private val sessionsDir: File
        get() = File(context.filesDir, "chat_sessions").apply { mkdirs() }
    private val activeSessionFile: File
        get() = File(context.filesDir, "active_chat_session_id.txt")

    suspend fun loadLastSession(): SavedChatSession? = withContext(Dispatchers.IO) {
        val activeId = loadActiveSessionIdSync()
        if (activeId != null) {
            loadSessionSync(activeId)?.let { return@withContext it }
        }
        sessionFile(activeId = ChatMemoryStore.DEFAULT_SESSION_ID).takeIf { it.exists() }?.let {
            return@withContext loadSessionSync(ChatMemoryStore.DEFAULT_SESSION_ID)
        }
        if (!sessionFile.exists()) return@withContext null
        runCatching {
            parseSession(sessionFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    suspend fun loadSession(sessionId: String): SavedChatSession? = withContext(Dispatchers.IO) {
        loadSessionSync(sessionId)
    }

    suspend fun listSessions(): List<ChatSessionSummary> = withContext(Dispatchers.IO) {
        sessionsDir.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val session = parseSession(file.readText(Charsets.UTF_8))
                    ChatSessionSummary(
                        id = session.id.ifBlank { file.nameWithoutExtension },
                        title = session.displayTitle(),
                        updatedAt = session.updatedAt.takeIf { it > 0L } ?: file.lastModified(),
                        messageCount = session.messages.size,
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun loadActiveSessionId(): String? = withContext(Dispatchers.IO) {
        loadActiveSessionIdSync()
    }

    suspend fun saveActiveSessionId(sessionId: String) = withContext(Dispatchers.IO) {
        activeSessionFile.writeText(sessionId, Charsets.UTF_8)
    }

    fun createSessionId(): String {
        return "session_${System.currentTimeMillis()}"
    }

    suspend fun saveLastSession(
        messages: List<ChatMessage>,
        systemPrompt: String,
        importantMemories: List<String>,
        sessionId: String = ChatMemoryStore.DEFAULT_SESSION_ID,
        title: String = deriveSessionTitle(messages),
    ) = withContext(Dispatchers.IO) {
        val target = sessionFile(activeId = sessionId)
        val partial = File(target.parentFile, "${target.name}.partial")
        partial.writeText(
            sessionToJson(messages, systemPrompt, importantMemories, sessionId, title, System.currentTimeMillis()).toString(2),
            Charsets.UTF_8,
        )
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "세션 저장 파일을 교체할 수 없습니다." }
        activeSessionFile.writeText(sessionId, Charsets.UTF_8)
    }

    fun sessionToJson(
        messages: List<ChatMessage>,
        systemPrompt: String,
        importantMemories: List<String>,
        sessionId: String = ChatMemoryStore.DEFAULT_SESSION_ID,
        title: String = deriveSessionTitle(messages),
        updatedAt: Long = System.currentTimeMillis(),
    ): JSONObject {
        return JSONObject()
            .put("version", 3)
            .put("id", sessionId)
            .put("title", title)
            .put("updatedAt", updatedAt)
            .put("systemPrompt", systemPrompt)
            .put("importantMemories", JSONArray().apply {
                importantMemories.forEach { put(it) }
            })
            .put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", message.role.name)
                            .put("content", message.content)
                            .put("imagePath", message.imagePath)
                            .put("audioPath", message.audioPath)
                            .put("attachmentLabel", message.attachmentLabel),
                    )
                }
            })
    }

    fun parseSession(text: String): SavedChatSession {
        val json = JSONObject(text)
        val messages = buildList {
            val array = json.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = runCatching {
                    ChatRole.valueOf(item.optString("role", ChatRole.User.name))
                }.getOrDefault(ChatRole.User)
                add(
                    ChatMessage(
                        role = role,
                        content = item.optString("content"),
                        imagePath = item.optNullableString("imagePath"),
                        audioPath = item.optNullableString("audioPath"),
                        attachmentLabel = item.optNullableString("attachmentLabel"),
                    ),
                )
            }
        }
        val memories = buildList {
            val array = json.optJSONArray("importantMemories") ?: JSONArray()
            for (index in 0 until array.length()) {
                val memory = array.optString(index).trim()
                if (memory.isNotBlank()) add(memory)
            }
        }
        return SavedChatSession(
            id = json.optString("id", ChatMemoryStore.DEFAULT_SESSION_ID),
            title = json.optString("title"),
            updatedAt = json.optLong("updatedAt", 0L),
            messages = messages,
            systemPrompt = json.optString("systemPrompt"),
            importantMemories = memories,
        )
    }

    private fun sessionFile(activeId: String): File {
        return File(sessionsDir, "$activeId.json")
    }

    private fun loadSessionSync(sessionId: String): SavedChatSession? {
        val file = sessionFile(sessionId)
        if (!file.exists()) return null
        return runCatching { parseSession(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun loadActiveSessionIdSync(): String? {
        if (!activeSessionFile.exists()) return null
        return activeSessionFile.readText(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }
}

fun deriveSessionTitle(messages: List<ChatMessage>): String {
    val firstUser = messages.firstOrNull { it.role == ChatRole.User }?.content?.trim()
    if (!firstUser.isNullOrBlank()) return firstUser.replace(Regex("\\s+"), " ").take(28)
    return "새 상담"
}

private fun SavedChatSession.displayTitle(): String {
    return title.ifBlank { deriveSessionTitle(messages) }
}

private fun JSONObject.optNullableString(name: String): String? {
    return if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
