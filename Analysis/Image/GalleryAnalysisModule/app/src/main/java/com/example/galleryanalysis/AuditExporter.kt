package com.example.galleryanalysis

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 개발/검증용 감사 로그 exporter.
 * 원본 이미지를 저장하지 않고, 라벨/점수/이벤트 매핑 설명만 app-specific external directory에 저장한다.
 */
class AuditExporter(private val context: Context) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun export(
        filtered: List<Pair<GalleryImage, FilterResult>>,
        classified: List<ClassifiedImage>,
        events: List<ActivityEvent>,
        wellbeingScores: List<WellbeingDomainScore>,
        quality: AnalysisQualityReport,
        summary: AnalysisSummary
    ): AuditExportResult {
        return try {
            val root = File(context.getExternalFilesDir(null), "GalleryAnalysisAudit")
            if (!root.exists()) root.mkdirs()
            val stamp = System.currentTimeMillis().toString()
            val dir = File(root, "run_$stamp")
            dir.mkdirs()

            val perImage = File(dir, "per_image_audit.csv")
            val perEvent = File(dir, "per_event_audit.csv")
            val summaryJson = File(dir, "scenario_summary.json")

            val eventByImage = buildEventLookup(events)
            val classifiedById = classified.associateBy { it.image.imageId }

            perImage.writeText(buildPerImageCsv(filtered, classifiedById, eventByImage))
            perEvent.writeText(buildPerEventCsv(events))
            summaryJson.writeText(gson.toJson(mapOf(
                "summary" to summary,
                "quality" to quality,
                "wellbeingDomainScores" to wellbeingScores,
                "note" to "Audit files contain metadata, labels and derived scores only. Raw images are not exported."
            )))

            AuditExportResult(
                saved = true,
                directoryPath = dir.absolutePath,
                perImageCsvPath = perImage.absolutePath,
                perEventCsvPath = perEvent.absolutePath,
                scenarioSummaryJsonPath = summaryJson.absolutePath,
                perImageRows = filtered.size,
                perEventRows = events.size
            )
        } catch (t: Throwable) {
            AuditExportResult(
                saved = false,
                directoryPath = null,
                perImageCsvPath = null,
                perEventCsvPath = null,
                scenarioSummaryJsonPath = null,
                perImageRows = 0,
                perEventRows = 0,
                warning = t.message ?: "audit export failed"
            )
        }
    }

    private fun buildEventLookup(events: List<ActivityEvent>): (ClassifiedImage) -> ActivityEvent? = { image ->
        val t = image.image.bestTakenAtMillis
        if (t == null) {
            null
        } else {
            events
                .filter { it.category == image.category }
                .minByOrNull { event ->
                    if (t in event.startedAtMillis..event.endedAtMillis) 0L else minOf(abs(t - event.startedAtMillis), abs(t - event.endedAtMillis))
                }
                ?.takeIf { event ->
                    val gap = if (t in event.startedAtMillis..event.endedAtMillis) 0L else minOf(abs(t - event.startedAtMillis), abs(t - event.endedAtMillis))
                    gap <= 10 * 60 * 1000L || event.representativeImageId == image.image.imageId
                }
        }
    }

    private fun buildPerImageCsv(
        filtered: List<Pair<GalleryImage, FilterResult>>,
        classifiedById: Map<Long, ClassifiedImage>,
        eventByImage: (ClassifiedImage) -> ActivityEvent?
    ): String = buildString {
        appendLine(listOf(
            "image_id", "file_name", "relative_path", "taken_at_local", "source_bucket", "excluded_flag", "exclusion_reason",
            "event_id", "mlkit_labels_topk", "objects_topk", "face_count", "time_bucket", "activity_category",
            "dominant_wellbeing_domain", "category_confidence", "review_notes"
        ).joinToString(","))
        filtered.forEach { (image, filter) ->
            val c = classifiedById[image.imageId]
            val event = c?.let(eventByImage)
            val labels = c?.labels.orEmpty().sortedByDescending { it.confidence }.take(8)
                .joinToString(";") { "${it.text}:${String.format("%.2f", it.confidence)}" }
            val objects = c?.objects.orEmpty().sortedByDescending { it.confidence }.take(5)
                .joinToString(";") { "${it.category}:${String.format("%.2f", it.confidence)}" }
            val domain = c?.let { activityToDomain(it.category) }?.name.orEmpty()
            val row = listOf(
                image.imageId.toString(),
                image.fileName.orEmpty(),
                image.relativePath.orEmpty(),
                image.bestTakenAtMillis?.let { formatTime(it) }.orEmpty(),
                inferSourceBucket(image, filter),
                if (filter.isAnalyzable) "N" else "Y",
                filter.reason.orEmpty(),
                event?.eventId.orEmpty(),
                labels,
                objects,
                c?.faceCount?.toString().orEmpty(),
                image.bestTakenAtMillis?.let { timeBucket(it) }.orEmpty(),
                c?.category?.name.orEmpty(),
                domain,
                c?.confidence?.let { String.format("%.2f", it) }.orEmpty(),
                c?.reviewNotes.orEmpty().joinToString(";")
            )
            appendLine(row.joinToString(",") { csv(it) })
        }
    }

    private fun buildPerEventCsv(events: List<ActivityEvent>): String = buildString {
        appendLine(listOf(
            "event_id", "start_at", "end_at", "duration_min", "image_count", "category", "dominant_wellbeing_domain",
            "dominant_labels", "sub_activities", "time_bucket", "confidence", "representative_image_id"
        ).joinToString(","))
        events.forEach { event ->
            val durationMin = ((event.endedAtMillis - event.startedAtMillis).coerceAtLeast(0L) / 60000.0)
            val labels = event.labels.sortedByDescending { it.confidence }.take(10).joinToString(";") { "${it.text}:${String.format("%.2f", it.confidence)}" }
            val row = listOf(
                event.eventId,
                formatTime(event.startedAtMillis),
                formatTime(event.endedAtMillis),
                String.format("%.1f", durationMin),
                event.photoCount.toString(),
                event.category.name,
                activityToDomain(event.category).name,
                labels,
                event.subActivities.joinToString(";"),
                timeBucket(event.takenAtMillis),
                String.format("%.2f", event.confidence),
                event.representativeImageId.toString()
            )
            appendLine(row.joinToString(",") { csv(it) })
        }
    }

    private fun activityToDomain(category: ActivityCategory): WellbeingDomain = when (category) {
        ActivityCategory.STUDY_TASK -> WellbeingDomain.SCHOOL_STRESS
        ActivityCategory.PHYSICAL_ACTIVITY -> WellbeingDomain.PHYSICAL_ACTIVITY
        ActivityCategory.SOCIAL_ACTIVITY -> WellbeingDomain.SOCIAL_CONNECTION
        ActivityCategory.HOBBY_CREATIVE -> WellbeingDomain.RECOVERY_RESOURCE
        ActivityCategory.FOOD_MEAL -> WellbeingDomain.MEAL_ROUTINE
        ActivityCategory.PET_EMOTIONAL_RESOURCE -> WellbeingDomain.RECOVERY_RESOURCE
        ActivityCategory.NATURE_OUTDOOR -> WellbeingDomain.RECOVERY_RESOURCE
        ActivityCategory.INDOOR_STATIC -> WellbeingDomain.SEDENTARY_PATTERN
        ActivityCategory.UNKNOWN -> WellbeingDomain.GENERAL_CHECK_IN
    }

    private fun inferSourceBucket(image: GalleryImage, filter: FilterResult): String = when (filter.status) {
        ImageFilterStatus.EXCLUDED_SCREENSHOT -> "SCREENSHOT"
        ImageFilterStatus.EXCLUDED_DOWNLOAD -> "DOWNLOAD"
        ImageFilterStatus.EXCLUDED_MESSENGER -> "MESSENGER_EXPORT"
        else -> {
            val joined = "${image.relativePath.orEmpty()}/${image.fileName.orEmpty()}".lowercase()
            when {
                joined.contains("screenshot") || joined.contains("스크린샷") -> "SCREENSHOT"
                joined.contains("download") -> "DOWNLOAD"
                joined.contains("kakao") || joined.contains("messenger") -> "MESSENGER_EXPORT"
                joined.contains("dcim") || joined.contains("camera") -> "CAMERA"
                else -> "UNKNOWN"
            }
        }
    }

    private fun timeBucket(millis: Long): String = when (Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).hour) {
        in 0..4 -> "dawn"
        in 5..10 -> "morning"
        in 11..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    private fun formatTime(millis: Long): String = formatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
