package com.example.counseling.galleryanalysis

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs

/** 같은 시점에 비슷하게 찍힌 사진을 하나의 activity event로 묶는다. */
class DuplicateGrouper(
    private val timeWindowMillis: Long = 5 * 60 * 1000L,
    private val hashDistanceThreshold: Int = 12
) {
    fun group(images: List<ClassifiedImage>): List<ActivityEvent> {
        val candidates = images
            .filter { it.category != ActivityCategory.UNKNOWN && it.image.bestTakenAtMillis != null }
            .sortedBy { it.image.bestTakenAtMillis }

        if (candidates.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<ClassifiedImage>>()
        var current = mutableListOf(candidates.first())
        for (item in candidates.drop(1)) {
            if (canJoin(current.last(), item) || canJoin(current.first(), item)) {
                current += item
            } else {
                groups += current
                current = mutableListOf(item)
            }
        }
        groups += current

        return groups.map { group ->
            val representative = group.maxByOrNull { it.confidence } ?: group.first()
            val times = group.mapNotNull { it.image.bestTakenAtMillis }
            val taken = representative.image.bestTakenAtMillis ?: times.first()
            ActivityEvent(
                eventId = createStableEventId(representative.image.imageId, taken),
                representativeImageId = representative.image.imageId,
                photoCount = group.size,
                category = representative.category,
                takenAtMillis = taken,
                startedAtMillis = times.minOrNull() ?: taken,
                endedAtMillis = times.maxOrNull() ?: taken,
                labels = group.flatMap { it.labels }.sortedByDescending { it.confidence }.take(30),
                subActivities = group.flatMap { it.subActivities }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(5),
                confidence = group.map { it.confidence }.average().toFloat().coerceIn(0f, 1f),
                representativeEvidence = representative.scoreBreakdown.firstOrNull { it.category == representative.category }?.evidence.orEmpty()
            )
        }
    }

    private fun canJoin(a: ClassifiedImage, b: ClassifiedImage): Boolean {
        if (a.category != b.category) return false
        val ta = a.image.bestTakenAtMillis ?: return false
        val tb = b.image.bestTakenAtMillis ?: return false
        if (abs(tb - ta) > timeWindowMillis) return false

        val ha = a.averageHash
        val hb = b.averageHash
        if (ha != null && hb != null) {
            val distance = ImageHasher.hammingDistance(ha, hb)
            if (distance <= hashDistanceThreshold) return true
        }
        // 해시가 다르더라도 같은 카테고리의 짧은 연속 촬영은 activity event로 합친다.
        return abs(tb - ta) <= 90_000L && a.confidence >= 0.55f && b.confidence >= 0.55f
    }

    private fun createStableEventId(imageId: Long, takenAtMillis: Long): String {
        val raw = "gallery-event-$imageId-$takenAtMillis".toByteArray(StandardCharsets.UTF_8)
        return UUID.nameUUIDFromBytes(raw).toString()
    }
}
