package com.example.counseling

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RelevantMemory(
    val role: ChatRole,
    val content: String,
    val attachmentLabel: String?,
)

class ChatMemoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "chat_memory.db",
    null,
    1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                attachment_label TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE chat_messages_fts USING fts4(
                content,
                role,
                attachment_label
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_chat_messages_session_position ON chat_messages(session_id, position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chat_messages_fts")
        db.execSQL("DROP TABLE IF EXISTS chat_messages")
        onCreate(db)
    }

    suspend fun reindexSession(
        messages: List<ChatMessage>,
        sessionId: String = DEFAULT_SESSION_ID,
    ) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL(
                """
                DELETE FROM chat_messages_fts
                WHERE rowid IN (
                    SELECT id FROM chat_messages WHERE session_id = ?
                )
                """.trimIndent(),
                arrayOf(sessionId),
            )
            writableDatabase.delete("chat_messages", "session_id = ?", arrayOf(sessionId))
            val now = System.currentTimeMillis()
            messages.forEachIndexed { index, message ->
                if (message.content.isBlank()) return@forEachIndexed
                val statement = writableDatabase.compileStatement(
                    """
                    INSERT INTO chat_messages (
                        session_id,
                        position,
                        role,
                        content,
                        attachment_label,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                )
                statement.bindString(1, sessionId)
                statement.bindLong(2, index.toLong())
                statement.bindString(3, message.role.name)
                statement.bindString(4, message.content)
                message.attachmentLabel?.let { statement.bindString(5, it) } ?: statement.bindNull(5)
                statement.bindLong(6, now + index)
                val rowId = statement.executeInsert()

                val ftsStatement = writableDatabase.compileStatement(
                    "INSERT INTO chat_messages_fts(rowid, content, role, attachment_label) VALUES (?, ?, ?, ?)",
                )
                ftsStatement.bindLong(1, rowId)
                ftsStatement.bindString(2, message.content)
                ftsStatement.bindString(3, message.role.name)
                message.attachmentLabel?.let { ftsStatement.bindString(4, it) } ?: ftsStatement.bindNull(4)
                ftsStatement.executeInsert()
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun searchRelevant(
        queryText: String,
        limit: Int = 4,
        sessionId: String = DEFAULT_SESSION_ID,
    ): List<RelevantMemory> = withContext(Dispatchers.IO) {
        val query = buildFtsQuery(queryText)
        val ftsResults = if (query != null) searchByFts(query, limit, sessionId) else emptyList()
        if (ftsResults.isNotEmpty()) {
            ftsResults
        } else {
            searchByLike(queryText, limit, sessionId)
        }
    }

    private fun searchByFts(query: String, limit: Int, sessionId: String): List<RelevantMemory> {
        val sql = """
            SELECT m.role, m.content, m.attachment_label
            FROM chat_messages_fts f
            JOIN chat_messages m ON m.id = f.rowid
            WHERE f.content MATCH ? AND m.session_id = ?
            ORDER BY m.position DESC
            LIMIT ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(query, sessionId, limit.toString())).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toRelevantMemory())
                }
            }
        }
    }

    private fun searchByLike(queryText: String, limit: Int, sessionId: String): List<RelevantMemory> {
        val terms = extractSearchTerms(queryText).take(3)
        if (terms.isEmpty()) return emptyList()
        val where = terms.joinToString(separator = " OR ") { "content LIKE ?" }
        val args = terms.map { "%$it%" } + listOf(sessionId, limit.toString())
        val sql = """
            SELECT role, content, attachment_label
            FROM chat_messages
            WHERE ($where) AND session_id = ?
            ORDER BY position DESC
            LIMIT ?
        """.trimIndent()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toRelevantMemory())
                }
            }
        }
    }

    private fun android.database.Cursor.toRelevantMemory(): RelevantMemory {
        val role = runCatching { ChatRole.valueOf(getString(0)) }.getOrDefault(ChatRole.User)
        val content = getString(1)
        val attachmentLabel = if (isNull(2)) null else getString(2)
        return RelevantMemory(role, content, attachmentLabel)
    }

    companion object {
        const val DEFAULT_SESSION_ID = "default"
    }
}

private fun buildFtsQuery(text: String): String? {
    val terms = extractSearchTerms(text)
        .filter { it.length >= 2 }
        .take(5)
    if (terms.isEmpty()) return null
    return terms.joinToString(separator = " OR ")
}

private fun extractSearchTerms(text: String): List<String> {
    return Regex("[가-힣A-Za-z0-9]+")
        .findAll(text)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}
