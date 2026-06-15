package com.example.counseling.galleryanalysis

import kotlin.math.abs
import kotlin.math.max

class CounselingFeatureMapper {
    fun map(
        period: PeriodChangeAnalysis,
        preferences: List<PreferenceResult>,
        quality: AnalysisQualityReport,
        visualRiskCues: List<VisualRiskCue>
    ): BehaviorScores {
        fun delta(category: ActivityCategory): Double = period.categoryChanges.firstOrNull { it.category == category }?.deltaPercentPoint ?: 0.0
        fun inc(category: ActivityCategory, threshold: Double): Double = (delta(category) / threshold).coerceIn(0.0, 1.0)
        fun dec(category: ActivityCategory, threshold: Double): Double = (-delta(category) / threshold).coerceIn(0.0, 1.0)
        fun change(category: ActivityCategory, threshold: Double): Double = (abs(delta(category)) / threshold).coerceIn(0.0, 1.0)

        val activationPositive = max(inc(ActivityCategory.PHYSICAL_ACTIVITY, 25.0), inc(ActivityCategory.NATURE_OUTDOOR, 18.0))
        val activationDrop = max(dec(ActivityCategory.PHYSICAL_ACTIVITY, 18.0), dec(ActivityCategory.NATURE_OUTDOOR, 15.0))
        val activityActivationScore = (0.50 + activationPositive * 0.30 - activationDrop * 0.45).coerceIn(0.0, 1.0)

        val lifeRhythm = (period.nightChange.deltaPercentPoint / 18.0).coerceIn(0.0, 1.0)
        val socialWithdrawal = dec(ActivityCategory.SOCIAL_ACTIVITY, 18.0)
        val academicLoad = max(inc(ActivityCategory.STUDY_TASK, 18.0), lifeRhythm * recentStudyPresence(period))
        val interestCandidateCategories = setOf(
            ActivityCategory.HOBBY_CREATIVE,
            ActivityCategory.PET_EMOTIONAL_RESOURCE,
            ActivityCategory.PHYSICAL_ACTIVITY,
            ActivityCategory.NATURE_OUTDOOR,
            ActivityCategory.SOCIAL_ACTIVITY
        )
        val topInterest = preferences
            .map { it.category }
            .filter { it in interestCandidateCategories }
            .take(3)
        val interestDrop = if (topInterest.isEmpty()) 0.0 else topInterest.map { dec(it, 20.0) }.average().coerceIn(0.0, 1.0)
        val mealChange = change(ActivityCategory.FOOD_MEAL, 22.0)
        val cueScore = visualRiskCues.take(4).sumOf { it.confidence * (1.0 + it.imageCount.coerceAtMost(5) / 10.0) / 4.0 }.coerceIn(0.0, 1.0)
        val qualityPenalty = if (quality.classificationCoverage < 0.45) 0.10 else 0.0
        val overall = listOf(lifeRhythm, socialWithdrawal, academicLoad, interestDrop, mealChange * 0.55, cueScore * 0.9).maxOrNull().orZero()

        return BehaviorScores(
            activityActivationScore = activityActivationScore,
            lifeRhythmInstabilityScore = lifeRhythm,
            socialWithdrawalSignalScore = socialWithdrawal,
            academicLoadSignalScore = academicLoad.coerceIn(0.0, 1.0),
            interestContinuityDropScore = interestDrop,
            mealPatternChangeScore = mealChange,
            visualRiskCueScore = cueScore,
            overallCounselingPriorityScore = (overall - qualityPenalty).coerceIn(0.0, 1.0)
        )
    }

    private fun recentStudyPresence(period: PeriodChangeAnalysis): Double {
        val study = period.categoryChanges.firstOrNull { it.category == ActivityCategory.STUDY_TASK } ?: return 0.0
        return if (study.recentCount > 0) 1.0 else 0.0
    }
}

private fun Double?.orZero(): Double = this ?: 0.0
