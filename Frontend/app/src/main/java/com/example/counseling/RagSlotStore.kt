package com.example.counseling

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class RagSlot(val fileName: String, val label: String) {
    Health("health_summary.json", "Health Connect"),
    Phenotype("phenotype_summary.json", "생활 패턴"),
    Gallery("gallery_analysis.json", "Gallery Image Analysis"),
}

data class RagSlotDocument(
    val slot: RagSlot,
    val updatedAt: Long,
    val contextText: String,
    val source: String,
)

suspend fun replaceRagSlot(
    context: Context,
    slot: RagSlot,
    contextText: String?,
    source: String,
): RagSlotDocument? = withContext(Dispatchers.IO) {
    val normalized = contextText?.trim().orEmpty()
    if (normalized.isBlank()) return@withContext null

    val document = RagSlotDocument(
        slot = slot,
        updatedAt = System.currentTimeMillis(),
        contextText = normalized,
        source = source,
    )
    val json = JSONObject()
        .put("slot", slot.name)
        .put("label", slot.label)
        .put("updatedAt", document.updatedAt)
        .put("source", source)
        .put("contextText", normalized)

    val target = ragSlotFile(context, slot)
    val partial = File(target.parentFile, "${target.name}.partial")
    partial.writeText(json.toString(2), Charsets.UTF_8)
    if (target.exists()) target.delete()
    check(partial.renameTo(target)) { "RAG 슬롯을 교체할 수 없습니다: ${slot.name}" }
    document
}

suspend fun readRagSlot(context: Context, slot: RagSlot): RagSlotDocument? = withContext(Dispatchers.IO) {
    runCatching {
        val file = ragSlotFile(context, slot)
        if (!file.exists()) return@withContext null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        RagSlotDocument(
            slot = slot,
            updatedAt = json.optLong("updatedAt", 0L),
            contextText = json.optString("contextText").trim(),
            source = json.optString("source", slot.label),
        )
    }.getOrNull()?.takeIf { it.contextText.isNotBlank() }
}

private fun ragSlotFile(context: Context, slot: RagSlot): File {
    val dir = File(context.filesDir, "rag_slots").apply { mkdirs() }
    return File(dir, slot.fileName)
}
