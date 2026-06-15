package com.example.galleryanalysis

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.max

/**
 * 장기 6개월 event 기준으로 반복적으로 기록된 활동/관심사 후보를 계산한다.
 *
 * 핵심 원칙:
 * - 선호도 후보는 단순 사진 수가 아니라 "주기성 + 반복성"으로 본다.
 * - 10분 안에 많이 찍은 burst는 DuplicateGrouper에서 하나의 event로 묶인 뒤 photoCount로만 남는다.
 * - 따라서 eventCount, activeWeekCount, activeMonthCount, 관찰 기간 span을 함께 반영한다.
 */
class PreferenceAnalyzer {
    fun analyze(events: List<ActivityEvent>, topN: Int = 8): List<PreferenceResult> {
        val known = events.filter { it.category != ActivityCategory.UNKNOWN }
        val totalEvents = known.size.coerceAtLeast(1)

        return known.groupBy { it.category }
            .map { (category, group) ->
                val sorted = group.sortedBy { it.startedAtMillis }
                val eventCount = group.size
                val photoCount = group.sumOf { it.photoCount }
                val activeWeeks = group.map { weekKey(it.startedAtMillis) }.distinct().size
                val activeMonths = group.map { monthKey(it.startedAtMillis) }.distinct().size
                val spanDays = observationSpanDays(sorted)
                val averagePhotosPerEvent = photoCount.toDouble() / eventCount.coerceAtLeast(1)

                val eventRatio = eventCount.toDouble() / totalEvents
                val repeatedEventScore = (ln(1.0 + eventCount) / ln(1.0 + 24.0)).coerceIn(0.0, 1.0)
                val weekRecurrenceScore = (activeWeeks / 8.0).coerceIn(0.0, 1.0)
                val monthContinuityScore = (activeMonths / 4.0).coerceIn(0.0, 1.0)
                val spanScore = (spanDays / 90.0).coerceIn(0.0, 1.0)
                val burstPenalty = if (eventCount <= 2 && averagePhotosPerEvent >= 8.0) 0.20 else 0.0

                val recurrenceScore = (
                    eventRatio * 0.25 +
                        repeatedEventScore * 0.25 +
                        weekRecurrenceScore * 0.25 +
                        monthContinuityScore * 0.20 +
                        spanScore * 0.05 -
                        burstPenalty
                    ).coerceIn(0.0, 1.0)

                val labels = group.flatMap { it.labels }
                    .filterNot { isGenericLabel(it.text) }
                    .groupBy { it.text.lowercase().replaceFirstChar { c -> c.uppercase() } }
                    .map { (label, values) -> LabelAggregate(label, values.size, values.map { it.confidence }.average().toFloat()) }
                    .sortedWith(compareByDescending<LabelAggregate> { it.count }.thenByDescending { it.averageConfidence })
                    .take(8)
                val subActivities = group.flatMap { it.subActivities }
                    .filterNot { isGenericSubActivity(it) }
                    .groupingBy { it }
                    .eachCount()
                    .map { (name, count) -> SubActivityAggregate(name, count, count.toDouble() / eventCount.coerceAtLeast(1)) }
                    .sortedByDescending { it.count }
                    .take(6)
                val confidence = group.map { it.confidence }.average().coerceIn(0.0, 1.0)
                PreferenceResult(
                    category = category,
                    categoryNameKo = category.koName,
                    eventCount = eventCount,
                    photoCount = photoCount,
                    ratio = eventRatio,
                    confidence = confidence,
                    recurrenceScore = recurrenceScore,
                    activeWeekCount = activeWeeks,
                    activeMonthCount = activeMonths,
                    observationSpanDays = spanDays,
                    averagePhotosPerEvent = averagePhotosPerEvent,
                    topLabels = labels,
                    topSubActivities = subActivities,
                    interpretation = "${category.koName} 관련 장면이 여러 시점에 반복적으로 기록된 활동 후보입니다. 선호를 단정하지 않고 상담 질문의 맥락 후보로만 사용하세요."
                )
            }
            .sortedWith(
                compareByDescending<PreferenceResult> { it.recurrenceScore }
                    .thenByDescending { it.activeWeekCount }
                    .thenByDescending { it.eventCount }
                    .thenByDescending { it.confidence }
            )
            .take(topN)
    }

    private fun weekKey(millis: Long): Long = millis / (7L * 24L * 60L * 60L * 1000L)

    private fun monthKey(millis: Long): String = Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .let { "${it.year}-${it.monthValue}" }

    private fun observationSpanDays(sorted: List<ActivityEvent>): Int {
        if (sorted.isEmpty()) return 0
        val spanMillis = (sorted.last().endedAtMillis - sorted.first().startedAtMillis).coerceAtLeast(0L)
        return max(1, (spanMillis / (24L * 60L * 60L * 1000L)).toInt())
    }

    private fun isGenericLabel(label: String): Boolean {
        val t = label.lowercase()
        return t in setOf("photo", "photograph", "photography", "image", "camera", "snapshot", "picture")
    }

    private fun isGenericSubActivity(name: String): Boolean {
        val t = name.lowercase()
        return t in setOf("촬영/사진", "사진", "image", "photo", "camera")
    }
}
