package com.example.counseling.galleryanalysis

import kotlin.math.abs

/** 기간별 변화도를 상담용 생활 패턴 신호로 변환한다. 진단이 아니라 질문 방향 후보만 만든다. */
class SignalGenerator {
    fun generate(
        period: PeriodChangeAnalysis,
        preferences: List<PreferenceResult>,
        quality: AnalysisQualityReport
    ): List<SignalResult> {
        val signals = mutableListOf<SignalResult>()
        fun delta(category: ActivityCategory): Double = period.categoryChanges.firstOrNull { it.category == category }?.deltaPercentPoint ?: 0.0

        if (delta(ActivityCategory.STUDY_TASK) >= 15.0) {
            signals += signal(SignalType.STUDY_LOAD_INCREASED, delta(ActivityCategory.STUDY_TASK), 15.0, listOf("academic_stress", "task_load", "sleep_rhythm"), "최근 14일의 학업/과제 관련 사진 비율이 이전 30일보다 증가했습니다.")
        }
        if (period.nightChange.deltaPercentPoint >= 10.0) {
            signals += signal(SignalType.NIGHT_ACTIVITY_INCREASED, period.nightChange.deltaPercentPoint, 10.0, listOf("sleep_rhythm", "daily_routine", "fatigue"), "00:00~04:59 시간대 촬영 비율이 증가했습니다.")
        }
        if (delta(ActivityCategory.PHYSICAL_ACTIVITY) <= -15.0) {
            signals += signal(SignalType.PHYSICAL_ACTIVITY_DECREASED, delta(ActivityCategory.PHYSICAL_ACTIVITY), 15.0, listOf("physical_activity", "energy_level", "outdoor_routine"), "운동/신체활동 관련 사진 비율이 이전 기간보다 감소했습니다.")
        }
        if (delta(ActivityCategory.SOCIAL_ACTIVITY) <= -12.0) {
            signals += signal(SignalType.SOCIAL_ACTIVITY_DECREASED, delta(ActivityCategory.SOCIAL_ACTIVITY), 12.0, listOf("social_connection", "support_network", "isolation_check"), "사회활동 맥락 사진 비율이 이전 기간보다 감소했습니다.")
        }
        if (abs(delta(ActivityCategory.FOOD_MEAL)) >= 18.0) {
            signals += signal(SignalType.FOOD_PATTERN_CHANGED, delta(ActivityCategory.FOOD_MEAL), 18.0, listOf("meal_pattern", "appetite", "daily_routine"), "음식/식사 관련 사진 비율 변화가 큽니다.")
        }

        val topInterestCategories = preferences.take(3).map { it.category }.filterNot { it == ActivityCategory.UNKNOWN }
        val interestDelta = topInterestCategories.sumOf { delta(it) }
        if (topInterestCategories.isNotEmpty() && interestDelta <= -15.0) {
            signals += signal(SignalType.INTEREST_ACTIVITY_DECREASED, interestDelta, 15.0, listOf("interest_activity", "motivation", "recovery_resources"), "장기적으로 자주 기록되던 활동 후보가 최근 기간에 감소했습니다.")
        }

        val highCount = signals.count { it.strength == SignalStrength.HIGH }
        if (highCount >= 2) {
            signals += SignalResult(
                signalType = SignalType.LIFE_PATTERN_SHIFT_DETECTED,
                strength = SignalStrength.HIGH,
                confidence = signals.map { it.confidence }.average().coerceIn(0.35, 0.95),
                recommendedQuestionDomain = listOf("life_pattern", "recent_change", "stress_context"),
                explanation = "강한 변화 신호가 두 개 이상 함께 나타나 생활 패턴 변화 가능성을 상담 질문으로 확인할 필요가 있습니다."
            )
        }

        if (quality.classificationCoverage < 0.45 || quality.averageConfidence < 0.35) {
            signals += SignalResult(
                signalType = SignalType.ANALYSIS_CONFIDENCE_LOW,
                strength = SignalStrength.MEDIUM,
                confidence = 1.0 - quality.averageConfidence.coerceIn(0.0, 1.0),
                recommendedQuestionDomain = listOf("general_check_in", "manual_questionnaire"),
                explanation = "이미지 분류 품질 지표가 낮아 갤러리 신호보다 일반 질문지/대화 확인을 우선해야 합니다."
            )
        }

        return signals
    }

    private fun signal(type: SignalType, delta: Double, threshold: Double, domains: List<String>, explanation: String): SignalResult {
        val magnitude = abs(delta)
        val strength = when {
            magnitude >= threshold * 1.45 -> SignalStrength.HIGH
            magnitude >= threshold -> SignalStrength.HIGH
            magnitude >= threshold * 0.65 -> SignalStrength.MEDIUM
            else -> SignalStrength.LOW
        }
        return SignalResult(
            signalType = type,
            strength = strength,
            confidence = (magnitude / (threshold * 1.6)).coerceIn(0.35, 0.95),
            recommendedQuestionDomain = domains,
            explanation = explanation
        )
    }
}
