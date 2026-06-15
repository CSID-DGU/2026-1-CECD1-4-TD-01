package com.example.counseling

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

suspend fun exportChatSession(
    context: Context,
    uri: Uri,
    messages: List<ChatMessage>,
    systemPrompt: String,
    importantMemories: List<String>,
) = withContext(Dispatchers.IO) {
    val json = JSONObject()
        .put("version", 2)
        .put("systemPrompt", systemPrompt)
        .put(
            "importantMemories",
            JSONArray().apply {
                importantMemories.forEach { put(it) }
            },
        )
        .put(
            "messages",
            JSONArray().apply {
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
            },
        )

    context.contentResolver.openOutputStream(uri).use { output ->
        requireNotNull(output) { "내보낼 파일을 열 수 없습니다." }
        output.write(json.toString(2).toByteArray(Charsets.UTF_8))
    }
}

suspend fun importChatSession(context: Context, uri: Uri): ImportedSession = withContext(Dispatchers.IO) {
    val text = context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "불러올 파일을 열 수 없습니다." }
        input.readBytes().toString(Charsets.UTF_8)
    }
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
                    imagePath = item.optNullableImportedString("imagePath"),
                    audioPath = item.optNullableImportedString("audioPath"),
                    attachmentLabel = item.optNullableImportedString("attachmentLabel"),
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
    ImportedSession(
        messages = messages,
        systemPrompt = json.optString("systemPrompt"),
        importantMemories = memories,
    )
}

private fun JSONObject.optNullableImportedString(name: String): String? {
    return if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}

fun queryOpenableDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}

