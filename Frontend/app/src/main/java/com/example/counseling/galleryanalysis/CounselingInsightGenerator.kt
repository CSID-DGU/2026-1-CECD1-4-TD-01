package com.example.counseling.galleryanalysis

class CounselingInsightGenerator {
    fun generate(
        scores: BehaviorScores,
        period: PeriodChangeAnalysis,
        preferences: List<PreferenceResult>,
        visualRiskCues: List<VisualRiskCue>,
        quality: AnalysisQualityReport
    ): List<CounselingInsight> {
        val out = mutableListOf<CounselingInsight>()
        fun delta(category: ActivityCategory): Double = period.categoryChanges.firstOrNull { it.category == category }?.deltaPercentPoint ?: 0.0
        fun priority(score: Double): CounselingPriority = when {
            score >= 0.70 -> CounselingPriority.HIGH
            score >= 0.40 -> CounselingPriority.MEDIUM
            else -> CounselingPriority.LOW
        }

        if (scores.lifeRhythmInstabilityScore >= 0.25 || period.nightChange.deltaPercentPoint >= 8.0) {
            out += insight(
                domain = CounselingDomain.SLEEP_RHYTHM,
                score = scores.lifeRhythmInstabilityScore,
                evidence = listOf(
                    "00~04시 촬영 비율 변화=${String.format("%.1f", period.nightChange.deltaPercentPoint)}%p",
                    "최근 야간 event=${period.nightChange.recentCount}, 기준 야간 event=${period.nightChange.baselineCount}"
                ),
                interpretation = "최근 생활 리듬이나 밤 시간대 부담을 확인해 볼 만한 신호입니다.",
                questions = listOf(
                    "최근 잠드는 시간이 평소보다 늦어졌다고 느끼나요?",
                    "밤에 깨어 있는 시간이 늘어난 이유가 공부, 걱정, 휴대폰 사용 중 어디에 가까울까요?"
                ),
                priority = priority(scores.lifeRhythmInstabilityScore)
            )
        }

        if (scores.academicLoadSignalScore >= 0.25 || delta(ActivityCategory.STUDY_TASK) >= 8.0) {
            out += insight(
                domain = CounselingDomain.ACADEMIC_STRESS,
                score = scores.academicLoadSignalScore,
                evidence = listOf("학업/과제 비율 변화=${String.format("%.1f", delta(ActivityCategory.STUDY_TASK))}%p"),
                interpretation = "공부량이나 과제 부담의 변화를 확인해 볼 만한 신호입니다.",
                questions = listOf(
                    "요즘 공부나 과제가 평소보다 부담스럽게 느껴지나요?",
                    "해야 할 일이 많아서 쉬는 시간이 줄었다고 느끼는지 궁금해요."
                ),
                priority = priority(scores.academicLoadSignalScore)
            )
        }

        if (scores.socialWithdrawalSignalScore >= 0.25) {
            out += insight(
                domain = CounselingDomain.SOCIAL_CONNECTION,
                score = scores.socialWithdrawalSignalScore,
                evidence = listOf("사회활동 비율 변화=${String.format("%.1f", delta(ActivityCategory.SOCIAL_ACTIVITY))}%p"),
                interpretation = "사람들과 보내는 시간이나 지지 관계의 변화를 확인해 볼 만한 신호입니다.",
                questions = listOf(
                    "최근 사람들과 보내는 시간이 예전과 달라졌다고 느끼나요?",
                    "요즘 혼자 있고 싶은 시간이 늘었는지, 아니면 만날 기회가 줄었는지 궁금해요."
                ),
                priority = priority(scores.socialWithdrawalSignalScore)
            )
        }

        val activationDelta = delta(ActivityCategory.PHYSICAL_ACTIVITY)
        if (activationDelta <= -8.0 || scores.activityActivationScore <= 0.35) {
            val score = 1.0 - scores.activityActivationScore
            out += insight(
                domain = CounselingDomain.BEHAVIORAL_ACTIVATION,
                score = score,
                evidence = listOf("운동/외출 관련 변화=${String.format("%.1f", activationDelta)}%p"),
                interpretation = "외출이나 몸을 움직이는 일상의 변화 여부를 확인해 볼 만한 신호입니다.",
                questions = listOf(
                    "요즘 밖에 나가거나 몸을 움직이는 일이 줄었다고 느끼나요?",
                    "최근 에너지가 예전보다 떨어졌다고 느끼는 순간이 있었나요?"
                ),
                priority = priority(score)
            )
        } else if (activationDelta >= 12.0) {
            out += insight(
                domain = CounselingDomain.BEHAVIORAL_ACTIVATION,
                score = (activationDelta / 35.0).coerceIn(0.0, 1.0),
                evidence = listOf("운동/외출 관련 변화=${String.format("%.1f", activationDelta)}%p"),
                interpretation = "최근 활동량이 늘어난 긍정적 자원일 수 있어 확인해 볼 만합니다.",
                questions = listOf(
                    "최근 몸을 움직이는 활동이 기분이나 스트레스 조절에 도움이 됐나요?",
                    "요즘 꾸준히 이어가고 싶은 활동이 있다면 무엇인가요?"
                ),
                priority = CounselingPriority.MEDIUM
            )
        }

        if (scores.interestContinuityDropScore >= 0.30) {
            val topNames = preferences.take(3).joinToString { it.categoryNameKo }
            out += insight(
                domain = CounselingDomain.INTEREST_CONTINUITY,
                score = scores.interestContinuityDropScore,
                evidence = listOf("장기 선호 후보=$topNames", "상위 관심 활동의 최근 감소 가능성"),
                interpretation = "예전에 즐기던 활동이 최근에도 회복 자원으로 남아 있는지 확인해 볼 만합니다.",
                questions = listOf(
                    "예전에 즐기던 활동이 요즘은 덜 끌리는 느낌이 있나요?",
                    "그래도 조금은 마음이 편해지는 활동이 남아 있다면 무엇인가요?"
                ),
                priority = priority(scores.interestContinuityDropScore)
            )
        }

        if (scores.mealPatternChangeScore >= 0.45) {
            out += insight(
                domain = CounselingDomain.MEAL_PATTERN,
                score = scores.mealPatternChangeScore,
                evidence = listOf("음식/식사 비율 변화=${String.format("%.1f", delta(ActivityCategory.FOOD_MEAL))}%p"),
                interpretation = "식사 리듬이나 외식/일상 루틴 변화 여부를 확인해 볼 만합니다.",
                questions = listOf("최근 식사 시간이 불규칙해졌거나 식욕이 달라졌다고 느끼나요?"),
                priority = priority(scores.mealPatternChangeScore)
            )
        }

        if (visualRiskCues.isNotEmpty()) {
            val mediumOrHigher = scores.visualRiskCueScore >= 0.55
            out += insight(
                domain = CounselingDomain.SAFETY_CHECK,
                score = scores.visualRiskCueScore,
                evidence = visualRiskCues.take(3).map { "${it.cueNameKo}: ${it.imageCount}장, conf=${String.format("%.2f", it.confidence)}" },
                interpretation = "위험 확정이 아니라 건강/안전 상태를 부드럽게 확인할 수 있는 보조 단서입니다.",
                questions = if (mediumOrHigher) listOf(
                    "요즘 몸이나 마음이 평소보다 지쳐 있다고 느끼는 순간이 있었나요?",
                    "최근 혼자 감당하기 힘들 정도로 마음이 버겁다고 느낀 적이 있었나요?"
                ) else listOf(
                    "요즘 컨디션이나 생활 리듬이 평소와 조금 달라졌다고 느끼나요?",
                    "몸이나 마음이 지치는 순간이 있었다면 어떤 때였는지 편하게 이야기해 줄 수 있을까요?"
                ),
                priority = if (mediumOrHigher) CounselingPriority.MEDIUM else CounselingPriority.LOW,
                evidenceLevel = CounselingEvidenceLevel.VISUAL_AUXILIARY_CUE
            )
        }

        if (quality.classificationCoverage < 0.45 || quality.averageConfidence < 0.35) {
            out += insight(
                domain = CounselingDomain.GENERAL_CHECK_IN,
                score = 0.55,
                evidence = listOf("분류 커버리지=${String.format("%.1f", quality.classificationCoverage * 100)}%", "평균 신뢰도=${String.format("%.2f", quality.averageConfidence)}"),
                interpretation = "이미지 분석 신뢰도가 낮아 일반 질문지와 직접 대화를 우선해야 합니다.",
                questions = listOf("요즘 가장 크게 신경 쓰이는 일이 있다면 어떤 것부터 이야기해 볼까요?"),
                priority = CounselingPriority.MEDIUM
            )
        }

        return out.sortedWith(compareByDescending<CounselingInsight> { it.priority.ordinal }.thenByDescending { it.confidence }).take(8)
    }

    private fun insight(
        domain: CounselingDomain,
        score: Double,
        evidence: List<String>,
        interpretation: String,
        questions: List<String>,
        priority: CounselingPriority,
        evidenceLevel: CounselingEvidenceLevel = CounselingEvidenceLevel.BEHAVIOR_PATTERN
    ): CounselingInsight = CounselingInsight(
        domain = domain,
        domainKo = domain.koName,
        priority = priority,
        confidence = score.coerceIn(0.20, 0.95),
        evidenceLevel = evidenceLevel,
        safeInterpretation = interpretation,
        evidence = evidence,
        suggestedQuestions = questions
    )
}
