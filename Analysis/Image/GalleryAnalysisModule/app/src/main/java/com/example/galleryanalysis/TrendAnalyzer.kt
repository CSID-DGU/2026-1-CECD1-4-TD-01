package com.example.galleryanalysis

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 월별 추세와 요일/시간대 분포를 만든다. */
class TrendAnalyzer(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    fun monthly(events: List<ActivityEvent>): List<MonthlyTrendPoint> {
        return events
            .filter { it.category != ActivityCategory.UNKNOWN }
            .groupBy { monthFormatter.format(Instant.ofEpochMilli(it.takenAtMillis).atZone(zoneId)) }
            .toSortedMap()
            .map { (month, group) ->
                val counts = ActivityCategory.values()
                    .filter { it != ActivityCategory.UNKNOWN }
                    .associateWith { category -> group.count { it.category == category } }
                    .filterValues { it > 0 }
                MonthlyTrendPoint(
                    month = month,
                    totalEvents = group.size,
                    categoryCounts = counts,
                    topCategory = counts.maxByOrNull { it.value }?.key
                )
            }
    }

    fun timeDistribution(events: List<ActivityEvent>): List<TimeBucketCount> {
        val known = events.filter { it.category != ActivityCategory.UNKNOWN }
        val total = known.size.coerceAtLeast(1)
        return known.groupBy { event ->
            val dt = Instant.ofEpochMilli(event.takenAtMillis).atZone(zoneId)
            dt.dayOfWeek.name to bucket(dt.hour)
        }.map { (key, group) ->
            TimeBucketCount(
                dayOfWeek = key.first,
                bucket = key.second,
                count = group.size,
                ratio = group.size.toDouble() / total
            )
        }.sortedWith(compareBy<TimeBucketCount> { it.dayOfWeek }.thenBy { it.bucket })
    }

    private fun bucket(hour: Int): String = when (hour) {
        in 0..5 -> "NIGHT_00_06"
        in 6..11 -> "MORNING_06_12"
        in 12..17 -> "AFTERNOON_12_18"
        else -> "EVENING_18_24"
    }
}
