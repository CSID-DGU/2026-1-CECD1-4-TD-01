package com.example.counseling

import android.content.Context
import com.example.counseling.galleryanalysis.AnalysisConfig
import com.example.counseling.galleryanalysis.GalleryAnalysisEngine
import com.example.counseling.galleryanalysis.GalleryAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class GalleryAnalysisCacheSnapshot(
    val fingerprint: String,
    val imageCount: Int,
    val updatedAt: Long,
    val contextText: String,
)

suspend fun refreshGalleryAnalysisCacheIfNeeded(
    context: Context,
    images: List<GalleryImage>,
    force: Boolean = false,
    onProgress: (String) -> Unit = {},
): GalleryAnalysisCacheSnapshot? = withContext(Dispatchers.Default) {
    if (images.isEmpty()) return@withContext null
    val fingerprint = galleryFingerprint(images)
    val cached = readGalleryAnalysisCache(context)
    if (!force && cached?.fingerprint == fingerprint) {
        onProgress("저장된 Gallery 분석 문서를 사용합니다.")
        return@withContext cached
    }

    onProgress("Gallery 이미지 분석을 시작합니다...")
    val result = GalleryAnalysisEngine(
        context = context.applicationContext,
        config = AnalysisConfig(
            maxImages = 600,
            enableAuditExport = false,
        ),
    ).analyze { percent, _ ->
        if (percent == 5 || percent == 15 || percent % 20 == 0 || percent == 100) {
            onProgress("Gallery 이미지 분석 중... $percent%")
        }
    }
    val snapshot = GalleryAnalysisCacheSnapshot(
        fingerprint = fingerprint,
        imageCount = images.size,
        updatedAt = System.currentTimeMillis(),
        contextText = buildGalleryPromptContext(result),
    )
    writeGalleryAnalysisCache(context, snapshot)
    replaceRagSlot(
        context = context,
        slot = RagSlot.Gallery,
        contextText = snapshot.contextText,
        source = "Gallery 이미지 ${images.size}장 분석 요약",
    )
    onProgress("Gallery 분석 문서를 갱신했습니다.")
    snapshot
}

suspend fun readGalleryAnalysisCache(context: Context): GalleryAnalysisCacheSnapshot? = withContext(Dispatchers.IO) {
    runCatching {
        val file = galleryAnalysisCacheFile(context)
        if (!file.exists()) return@withContext null
        val json = JSONObject(file.readText())
        GalleryAnalysisCacheSnapshot(
            fingerprint = json.getString("fingerprint"),
            imageCount = json.getInt("imageCount"),
            updatedAt = json.getLong("updatedAt"),
            contextText = json.getString("contextText"),
        )
    }.getOrNull()
}

private suspend fun writeGalleryAnalysisCache(
    context: Context,
    snapshot: GalleryAnalysisCacheSnapshot,
) = withContext(Dispatchers.IO) {
    val json = JSONObject()
        .put("fingerprint", snapshot.fingerprint)
        .put("imageCount", snapshot.imageCount)
        .put("updatedAt", snapshot.updatedAt)
        .put("contextText", snapshot.contextText)
    galleryAnalysisCacheFile(context).writeText(json.toString(2))
}

private fun buildGalleryPromptContext(result: GalleryAnalysisResult): String =
    result.counselingReport.llmContextSummary.trim()

private fun galleryFingerprint(images: List<GalleryImage>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    images
        .sortedBy { it.uri.toString() }
        .forEach { image ->
            digest.update(image.uri.toString().toByteArray())
            digest.update(0)
            digest.update(image.name.toByteArray())
            digest.update(0)
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun galleryAnalysisCacheFile(context: Context): File {
    return File(context.filesDir, "gallery_analysis_context.json")
}
