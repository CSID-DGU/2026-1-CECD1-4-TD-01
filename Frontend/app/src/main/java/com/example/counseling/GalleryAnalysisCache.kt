package com.example.counseling

import android.content.Context
import com.example.counseling.galleryanalysis.AnalysisConfig
import com.example.counseling.galleryanalysis.ActivityCategory
import com.example.counseling.galleryanalysis.GalleryAnalysisEngine
import com.example.counseling.galleryanalysis.GalleryAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class GalleryAnalysisCacheSnapshot(
    val fingerprint: String,
    val imageCount: Int,
    val updatedAt: Long,
    val contextText: String,
    val interestItems: List<GalleryInterestItem> = emptyList(),
)

data class GalleryInterestItem(
    val label: String,
    val photoCount: Int,
    val eventCount: Int,
    val ratio: Double,
    val confidence: Double,
    val level: String,
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
        interestItems = buildGalleryInterestItems(result),
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
            interestItems = json.optJSONArray("interestItems")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(
                            GalleryInterestItem(
                                label = item.optString("label"),
                                photoCount = item.optInt("photoCount"),
                                eventCount = item.optInt("eventCount"),
                                ratio = item.optDouble("ratio"),
                                confidence = item.optDouble("confidence"),
                                level = item.optString("level"),
                            ),
                        )
                    }
                }
            }.orEmpty(),
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
        .put(
            "interestItems",
            JSONArray().apply {
                snapshot.interestItems.forEach { item ->
                    put(
                        JSONObject()
                            .put("label", item.label)
                            .put("photoCount", item.photoCount)
                            .put("eventCount", item.eventCount)
                            .put("ratio", item.ratio)
                            .put("confidence", item.confidence)
                            .put("level", item.level),
                    )
                }
            },
        )
    galleryAnalysisCacheFile(context).writeText(json.toString(2))
}

private fun buildGalleryPromptContext(result: GalleryAnalysisResult): String =
    result.counselingReport.llmContextSummary.trim()

private fun buildGalleryInterestItems(result: GalleryAnalysisResult): List<GalleryInterestItem> {
    val analyzedImages = result.summary.analyzedImages.coerceAtLeast(1)
    val grouped = result.preferences.associateBy { it.category }
    return listOf(
        ActivityCategory.HOBBY_CREATIVE to "취미/창작",
        ActivityCategory.PET_EMOTIONAL_RESOURCE to "동물/반려동물",
        ActivityCategory.NATURE_OUTDOOR to "자연/외출",
        ActivityCategory.PHYSICAL_ACTIVITY to "운동/활동",
        ActivityCategory.FOOD_MEAL to "음식/카페",
        ActivityCategory.SOCIAL_ACTIVITY to "사람/모임",
    ).map { (category, label) ->
        val preference = grouped[category]
        val ratio = preference?.ratio ?: ((preference?.photoCount ?: 0).toDouble() / analyzedImages)
        val confidence = preference?.confidence ?: 0.0
        GalleryInterestItem(
            label = label,
            photoCount = preference?.photoCount ?: 0,
            eventCount = preference?.eventCount ?: 0,
            ratio = ratio.coerceIn(0.0, 1.0),
            confidence = confidence.coerceIn(0.0, 1.0),
            level = interestLevel(ratio, confidence),
        )
    }
}

private fun interestLevel(ratio: Double, confidence: Double): String {
    val score = (ratio * 0.7) + (confidence * 0.3)
    return when {
        score >= 0.45 -> "높음"
        score >= 0.22 -> "보통"
        score > 0.04 -> "낮음"
        else -> "거의 없음"
    }
}

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
