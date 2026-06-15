package com.example.counseling.galleryanalysis

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** 최근 14일과 그 이전 30일의 카테고리 비율 및 야간 촬영 비율을 비교한다. */
class PeriodChangeAnalyzer(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val recentDays: Long = 14,
    private val baselineDays: Long = 30
) {
    fun analyze(events: List<ActivityEvent>, nowMillis: Long = System.currentTimeMillis()): PeriodChangeAnalysis {
        val dayMs = 24L * 60L * 60L * 1000L
        val known = events.filter { it.category != ActivityCategory.UNKNOWN }
        val effectiveNowMillis = chooseEffectiveNowMillis(known, nowMillis, dayMs)
        val recentStart = effectiveNowMillis - recentDays * dayMs
        val baselineStart = recentStart - baselineDays * dayMs

        val recent = known.filter { it.takenAtMillis in recentStart..effectiveNowMillis }
        val baseline = known.filter { it.takenAtMillis >= baselineStart && it.takenAtMillis < recentStart }
        val recentTotal = recent.size
        val baselineTotal = baseline.size

        val changes = ActivityCategory.values()
            .filter { it != ActivityCategory.UNKNOWN }
            .map { category ->
                val recentCount = recent.count { it.category == category }
                val baselineCount = baseline.count { it.category == category }
                val recentRatio = if (recentTotal > 0) recentCount.toDouble() / recentTotal else 0.0
                val baselineRatio = if (baselineTotal > 0) baselineCount.toDouble() / baselineTotal else 0.0
                val delta = (recentRatio - baselineRatio) * 100.0
                PeriodChangeResult(
                    category = category,
                    categoryNameKo = category.koName,
                    recentCount = recentCount,
                    baselineCount = baselineCount,
                    recentRatio = recentRatio,
                    baselineRatio = baselineRatio,
                    deltaPercentPoint = delta,
                    direction = when {
                        abs(delta) < 3.0 -> "STABLE"
                        delta > 0 -> "INCREASED"
                        else -> "DECREASED"
                    }
                )
            }
            .sortedByDescending { abs(it.deltaPercentPoint) }

        return PeriodChangeAnalysis(changes, calculateNightChange(recent, baseline))
    }

    private fun calculateNightChange(recent: List<ActivityEvent>, baseline: List<ActivityEvent>): NightChangeResult {
        val recentTotal = recent.size
        val baselineTotal = baseline.size
        val recentNight = recent.count { isNight(it.takenAtMillis) }
        val baselineNight = baseline.count { isNight(it.takenAtMillis) }
        val recentRatio = if (recentTotal > 0) recentNight.toDouble() / recentTotal else 0.0
        val baselineRatio = if (baselineTotal > 0) baselineNight.toDouble() / baselineTotal else 0.0
        return NightChangeResult(recentNight, baselineNight, recentRatio, baselineRatio, (recentRatio - baselineRatio) * 100.0)
    }



    private fun chooseEffectiveNowMillis(events: List<ActivityEvent>, nowMillis: Long, dayMs: Long): Long {
        val latestEventMillis = events.maxOfOrNull { it.takenAtMillis } ?: return nowMillis
        // 테스트 데이터셋처럼 EXIF 촬영일은 과거인데 adb push 때문에 DATE_ADDED만 현재인 경우,
        // 기간 분석 기준점을 실제 최신 촬영 시각에 맞춘다. 일반 갤러리에서 최신 사진이 현재 근처면 nowMillis를 그대로 쓴다.
        return if (latestEventMillis < nowMillis - recentDays * dayMs) {
            latestEventMillis + dayMs
        } else {
            nowMillis
        }
    }

    private fun isNight(millis: Long): Boolean {
        val hour = Instant.ofEpochMilli(millis).atZone(zoneId).hour
        return hour in 0..4
    }
}
