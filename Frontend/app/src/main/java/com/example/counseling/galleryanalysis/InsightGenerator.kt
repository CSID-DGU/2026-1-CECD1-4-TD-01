package com.example.counseling.galleryanalysis

import kotlin.math.abs

/** 화면에 보여주기 좋은 요약 카드 생성. */
class InsightGenerator {
    fun generate(
        preferences: List<PreferenceResult>,
        period: PeriodChangeAnalysis,
        quality: AnalysisQualityReport
    ): List<InsightCard> {
        val cards = mutableListOf<InsightCard>()
        preferences.take(3).forEach { pref ->
            val subs = pref.topSubActivities.take(3).joinToString { it.name }.ifBlank { "세부 활동 없음" }
            cards += InsightCard(
                title = "반복 기록 활동 후보: ${pref.categoryNameKo}",
                message = "최근 6개월 event 중 ${(pref.ratio * 100).format1()}%를 차지했습니다.",
                evidence = listOf("event=${pref.eventCount}", "photo=${pref.photoCount}", "세부 후보=$subs", "confidence=${pref.confidence.format2()}")
            )
        }

        period.categoryChanges.filter { abs(it.deltaPercentPoint) >= 8.0 }.take(4).forEach { change ->
            cards += InsightCard(
                title = "기간 변화: ${change.categoryNameKo} ${change.direction}",
                message = "최근 14일 ${(change.recentRatio * 100).format1()}%, 이전 30일 ${(change.baselineRatio * 100).format1()}%입니다.",
                evidence = listOf("delta=${change.deltaPercentPoint.format1()}%p", "recent=${change.recentCount}", "baseline=${change.baselineCount}")
            )
        }

        if (abs(period.nightChange.deltaPercentPoint) >= 6.0) {
            cards += InsightCard(
                title = "야간 촬영 비율 변화",
                message = "00:00~04:59 촬영 비율 변화가 ${period.nightChange.deltaPercentPoint.format1()}%p입니다.",
                evidence = listOf("recentNight=${period.nightChange.recentCount}", "baselineNight=${period.nightChange.baselineCount}")
            )
        }

        if (quality.warnings.isNotEmpty()) {
            cards += InsightCard(
                title = "분석 품질 주의",
                message = "분석 결과 해석 시 품질 지표를 함께 확인해야 합니다.",
                evidence = quality.warnings.take(3)
            )
        }
        return cards
    }
}

private fun Double.format1(): String = String.format("%.1f", this)
private fun Double.format2(): String = String.format("%.2f", this)
