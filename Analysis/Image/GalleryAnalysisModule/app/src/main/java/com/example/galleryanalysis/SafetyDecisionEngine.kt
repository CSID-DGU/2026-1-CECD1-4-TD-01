package com.example.galleryanalysis

/**
 * v3.4.4 safety decision policy.
 *
 * Direct user text always has priority. Image-only cues can trigger a soft/direct safety check,
 * but cannot produce HIGH_SUPPORT or IMMEDIATE_DANGER.
 */
class SafetyDecisionEngine(
    private val keywordDetector: CrisisKeywordDetector = CrisisKeywordDetector(),
    private val responsePolicy: CrisisResponsePolicy = CrisisResponsePolicy()
) {
    fun assess(
        behaviorScores: BehaviorScores,
        visualRiskCues: List<VisualRiskCue>,
        userText: String? = null
    ): SafetyAssessment {
        val textRisk = keywordDetector.detect(userText)
        if (textRisk.level == SafetyLevel.IMMEDIATE_DANGER || textRisk.level == SafetyLevel.HIGH_SUPPORT) {
            val action = if (textRisk.level == SafetyLevel.IMMEDIATE_DANGER) SafetyAction.EMERGENCY_GUIDE else SafetyAction.RULE_BASED_CRISIS_RESPONSE
            return SafetyAssessment(
                level = textRisk.level,
                action = action,
                mustUseRuleBasedResponse = true,
                primaryTrigger = textRisk.trigger,
                reasons = textRisk.reasons + textRisk.matchedKeywords.map { "matched=$it" },
                responseGuideKo = responsePolicy.responseGuide(textRisk.level),
                emergencyResourcesKo = responsePolicy.emergencyResourcesKo(textRisk.level)
            )
        }

        val injuryCue = visualRiskCues.firstOrNull { it.cueType == VisualRiskCueType.POSSIBLE_INJURY_CONTEXT }
        val sharpCue = visualRiskCues.firstOrNull { it.cueType == VisualRiskCueType.POSSIBLE_SHARP_OBJECT }
        val medicalCue = visualRiskCues.firstOrNull { it.cueType == VisualRiskCueType.POSSIBLE_MEDICAL_CONTEXT }
        val medicationCue = visualRiskCues.firstOrNull { it.cueType == VisualRiskCueType.POSSIBLE_MEDICATION }

        val repeatedInjuryCue = injuryCue != null && injuryCue.imageCount >= 2 && injuryCue.confidence >= 0.34
        val singleStrongInjuryCue = injuryCue != null && injuryCue.confidence >= 0.58
        val repeatedSharpCue = sharpCue != null && sharpCue.imageCount >= 2 && sharpCue.confidence >= 0.45
        val visualCueScore = behaviorScores.visualRiskCueScore
        val combinedPattern = behaviorScores.lifeRhythmInstabilityScore >= 0.55 ||
            behaviorScores.socialWithdrawalSignalScore >= 0.55 ||
            behaviorScores.interestContinuityDropScore >= 0.60 ||
            behaviorScores.academicLoadSignalScore >= 0.70

        val level = when {
            // Image-only maximum is MEDIUM_CHECK_IN. It asks about safety; it does not diagnose.
            repeatedInjuryCue || singleStrongInjuryCue -> SafetyLevel.MEDIUM_CHECK_IN
            repeatedSharpCue && combinedPattern -> SafetyLevel.MEDIUM_CHECK_IN
            visualCueScore >= 0.60 && combinedPattern -> SafetyLevel.MEDIUM_CHECK_IN
            injuryCue != null || sharpCue != null -> SafetyLevel.LOW_OBSERVE
            medicalCue != null && medicalCue.imageCount >= 3 && combinedPattern -> SafetyLevel.LOW_OBSERVE
            medicationCue != null && medicationCue.imageCount >= 3 && combinedPattern -> SafetyLevel.LOW_OBSERVE
            else -> SafetyLevel.NONE
        }

        val action = when (level) {
            SafetyLevel.MEDIUM_CHECK_IN -> SafetyAction.DIRECT_CHECK_IN
            SafetyLevel.LOW_OBSERVE -> SafetyAction.SOFT_CHECK_IN
            else -> SafetyAction.NORMAL_COUNSELING
        }

        val reasons = buildList {
            if (injuryCue != null) add("상처/붕대/신체 단서 가능성: ${injuryCue.imageCount}장, conf=${String.format("%.2f", injuryCue.confidence)}")
            if (sharpCue != null) add("날카로운 물체 가능 단서: ${sharpCue.imageCount}장, conf=${String.format("%.2f", sharpCue.confidence)}")
            if (medicalCue != null) add("의료 맥락 가능 단서: ${medicalCue.imageCount}장, conf=${String.format("%.2f", medicalCue.confidence)}")
            if (medicationCue != null) add("약/복약 가능 단서: ${medicationCue.imageCount}장, conf=${String.format("%.2f", medicationCue.confidence)}")
            if (visualCueScore >= 0.35) add("이미지 기반 위험 보조 단서 점수=${String.format("%.2f", visualCueScore)}")
            if (combinedPattern) add("생활 리듬/사회성/관심사/학업 변화 신호가 함께 나타남")
            if (level != SafetyLevel.NONE) add("이미지 단독이므로 위험 확정 금지. 현재 안전 여부를 부드럽게 확인한다.")
            if (isEmpty()) add("고위험 직접 발화나 강한 위험 단서가 감지되지 않음")
        }

        return SafetyAssessment(
            level = level,
            action = action,
            mustUseRuleBasedResponse = false,
            primaryTrigger = if (level == SafetyLevel.NONE) "NONE" else "VISUAL_SAFETY_OVERLAY_IMAGE_ONLY",
            reasons = reasons,
            responseGuideKo = responsePolicy.responseGuide(level),
            emergencyResourcesKo = responsePolicy.emergencyResourcesKo(level)
        )
    }
}
