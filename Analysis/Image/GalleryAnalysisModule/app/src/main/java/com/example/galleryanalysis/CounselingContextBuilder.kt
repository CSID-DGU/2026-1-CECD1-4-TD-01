package com.example.galleryanalysis

class CounselingContextBuilder {
    fun build(
        scores: BehaviorScores,
        insights: List<CounselingInsight>,
        safety: SafetyAssessment,
        quality: AnalysisQualityReport,
        preferences: List<PreferenceResult> = emptyList(),
        period: PeriodChangeAnalysis? = null,
        phase: ConversationPhase = ConversationPhase.RAPPORT
    ): String {
        val safetyInstruction = when (safety.level) {
            SafetyLevel.IMMEDIATE_DANGER -> "즉시 위기 대응. 자유 상담 생성 금지. 응급/전문기관 연결 안내를 우선한다."
            SafetyLevel.HIGH_SUPPORT -> "룰 기반 위기 응답 우선. 사용자의 현재 안전 여부 확인, 신뢰할 수 있는 사람/전문기관 연결 안내."
            SafetyLevel.MEDIUM_CHECK_IN -> "직접적이지만 비판단적인 안전 확인 질문을 1회 포함한다. 위기 단정 금지."
            SafetyLevel.LOW_OBSERVE -> "부드러운 컨디션 확인만 한다. 자살/자해/성폭력 단어를 먼저 꺼내지 않는다."
            SafetyLevel.NONE -> "일반 상담 모드. 변화 신호를 단정하지 말고 사용자의 직접 경험 확인 질문으로 시작한다."
        }

        val focusPlan = selectFocusPlan(insights, scores, phase)
        val primaryLines = focusPlan.primary.mapIndexed { index, insight ->
            "${index + 1}. domain=${insight.domain}, priority=${insight.priority}, confidence=${fmt(insight.confidence)}, interpretation=${insight.safeInterpretation}, openingQuestion=${insight.suggestedQuestions.firstOrNull().orEmpty()}"
        }.ifEmpty {
            listOf("1. NONE - 특별한 고우선도 인사이트 없음. 일반 안부 확인부터 시작.")
        }
        val secondaryLines = focusPlan.secondary.map {
            "- domain=${it.domain}, priority=${it.priority}, confidence=${fmt(it.confidence)}; 초반 질문으로 바로 사용하지 말고 사용자가 관련 내용을 말할 때 후속 질문으로만 사용."
        }.ifEmpty { listOf("- none") }

        val preferenceLines = preferences.take(4).mapIndexed { index, pref ->
            "${index + 1}. ${pref.category}(${pref.categoryNameKo}) recurrence=${fmt(pref.recurrenceScore)}, events=${pref.eventCount}, photos=${pref.photoCount}, activeWeeks=${pref.activeWeekCount}, activeMonths=${pref.activeMonthCount}, spanDays=${pref.observationSpanDays}"
        }.ifEmpty { listOf("1. 충분히 반복적인 선호 후보 없음") }

        val behaviorLines = buildBehaviorChangeLines(scores, period)
        val suggestedOpening = focusPlan.suggestedOpening ?: defaultOpening(phase)
        val currentTask = currentTaskFor(phase, safety.level, focusPlan.primary)
        val doNotStart = buildDoNotStartWith(focusPlan.secondary, phase)

        return buildString {
            appendLine("[SYSTEM PURPOSE]")
            appendLine("너는 청소년/청년 상담 보조 챗봇이다. 아래 갤러리 분석은 진단이 아니라 상담 질문 방향을 정하는 보조 정보다. 사용자의 직접 발화와 질문지 응답을 항상 우선한다.")
            appendLine()

            appendLine("[SAFETY POLICY]")
            appendLine("- 이미지 분석만으로 자살, 자해, 성폭력, 폭력 피해를 확정하지 않는다.")
            appendLine("- safetyLevel이 HIGH_SUPPORT 또는 IMMEDIATE_DANGER이면 룰 기반 위기 응답이 LLM 자유 응답보다 우선한다.")
            appendLine("- safetyInstruction: $safetyInstruction")
            appendLine("- currentSafetyLevel=${safety.level}, action=${safety.action}, ruleBased=${safety.mustUseRuleBasedResponse}")
            appendLine()

            appendLine("[CONVERSATION PHASE]")
            appendLine("- currentPhase=$phase(${phase.koName})")
            appendLine("- phasePolicy=${phasePolicy(phase)}")
            appendLine()

            appendLine("[BEHAVIOR CHANGE SUMMARY]")
            behaviorLines.forEach { appendLine("- $it") }
            appendLine()

            appendLine("[PREFERENCE AND RECURRENCE SUMMARY]")
            appendLine("- 선호도 후보는 단순 사진 수가 아니라 event 반복성, activeWeek, activeMonth, spanDays를 함께 본다.")
            preferenceLines.forEach { appendLine("- $it") }
            appendLine()

            appendLine("[PRIMARY COUNSELING FOCUS]")
            primaryLines.forEach { appendLine("- $it") }
            appendLine()

            appendLine("[SECONDARY SIGNALS]")
            secondaryLines.forEach { appendLine(it) }
            appendLine()

            appendLine("[DO NOT START WITH]")
            doNotStart.forEach { appendLine("- $it") }
            appendLine()

            appendLine("[DATA QUALITY]")
            appendLine("- classificationCoverage=${fmt(quality.classificationCoverage)}")
            appendLine("- unknownRatio=${fmt(quality.unknownRatio)}")
            appendLine("- averageConfidence=${fmt(quality.averageConfidence)}")
            appendLine("- warning=${quality.warnings.joinToString(" | ").ifBlank { "none" }}")
            appendLine()

            appendLine("[CURRENT TASK]")
            appendLine(currentTask)
            appendLine()

            appendLine("[LATER PHASE GUIDE]")
            appendLine("- RAPPORT: 라포 형성. 분석 결과 설명 금지. 부드러운 질문 1개만 한다.")
            appendLine("- PROBLEM_EXPLORATION: 사용자의 답변을 반영하고 primaryFocus 중 하나만 깊게 묻는다.")
            appendLine("- PATTERN_REFLECTION: 사용자 발화 중심으로 패턴을 요약하고 맞는지 확인한다.")
            appendLine("- MICRO_ACTION_PLAN: 사용자가 원할 때만 부담 적은 행동 1개를 공동 설계한다.")
            appendLine()

            appendLine("[RESPONSE REQUIREMENTS]")
            appendLine("1. 갤러리 내용을 직접 언급하지 말고, '최근 생활 패턴이 조금 달라졌을 수 있어요'처럼 완곡하게 말한다.")
            appendLine("2. 한 번에 질문은 1개, 많아도 2개까지만 한다.")
            appendLine("3. 단정/진단/낙인 표현을 쓰지 않는다.")
            appendLine("4. 초반에는 생활 패턴 개선 조언을 하지 말고, 먼저 사용자의 경험을 듣는다.")
            appendLine("5. 생활 개선은 MICRO_ACTION_PLAN 단계에서만, 사용자의 동의를 받고 아주 작은 행동 1개로 제안한다.")
            appendLine("6. 위기 직접 발화가 나오면 safety policy를 즉시 따른다.")
            appendLine()

            appendLine("[SUGGESTED USER-FACING MESSAGE]")
            appendLine(suggestedOpening)
        }
    }

    private data class FocusPlan(
        val primary: List<CounselingInsight>,
        val secondary: List<CounselingInsight>,
        val suggestedOpening: String?
    )

    private fun selectFocusPlan(
        insights: List<CounselingInsight>,
        scores: BehaviorScores,
        phase: ConversationPhase
    ): FocusPlan {
        if (phase == ConversationPhase.SAFETY_CHECK) {
            val safety = insights.filter { it.domain == CounselingDomain.SAFETY_CHECK }.take(1)
            return FocusPlan(safety, insights - safety.toSet(), safety.firstOrNull()?.suggestedQuestions?.firstOrNull())
        }

        val sorted = insights.sortedWith(
            compareByDescending<CounselingInsight> { priorityWeight(it.priority) }
                .thenByDescending { it.confidence }
        )

        val highAcademicSleep = scores.lifeRhythmInstabilityScore >= 0.80 && scores.academicLoadSignalScore >= 0.70
        val highSocial = scores.socialWithdrawalSignalScore >= 0.75

        val preferredDomains = when {
            highAcademicSleep -> listOf(CounselingDomain.SLEEP_RHYTHM, CounselingDomain.ACADEMIC_STRESS)
            highSocial -> listOf(CounselingDomain.SOCIAL_CONNECTION, CounselingDomain.INTEREST_CONTINUITY)
            else -> emptyList()
        }

        val selected = mutableListOf<CounselingInsight>()
        preferredDomains.forEach { domain ->
            sorted.firstOrNull { it.domain == domain }?.let { if (selected.size < 2 && it !in selected) selected += it }
        }
        sorted.forEach { insight ->
            if (selected.size < 2 && insight !in selected && insight.priority != CounselingPriority.LOW) selected += insight
        }

        val primary = selected.take(2)
        val secondary = sorted.filterNot { it in primary }.take(6)
        val opening = primary.firstOrNull()?.suggestedQuestions?.firstOrNull()
        return FocusPlan(primary, secondary, opening)
    }

    private fun buildBehaviorChangeLines(scores: BehaviorScores, period: PeriodChangeAnalysis?): List<String> {
        val lines = mutableListOf<String>()
        lines += "overallCounselingPriority=${fmt(scores.overallCounselingPriorityScore)}"
        lines += "sleepRhythmInstability=${fmt(scores.lifeRhythmInstabilityScore)}"
        lines += "socialConnectionChange=${fmt(scores.socialWithdrawalSignalScore)}"
        lines += "academicLoad=${fmt(scores.academicLoadSignalScore)}"
        lines += "interestContinuityDrop=${fmt(scores.interestContinuityDropScore)}"
        lines += "mealPatternChange=${fmt(scores.mealPatternChangeScore)}"
        lines += "visualAuxiliaryRiskCue=${fmt(scores.visualRiskCueScore)}"
        if (period != null) {
            val topChanges = period.categoryChanges
                .sortedByDescending { kotlin.math.abs(it.deltaPercentPoint) }
                .take(4)
                .joinToString("; ") { "${it.category} ${fmt1(it.deltaPercentPoint)}%p(recent=${it.recentCount}, baseline=${it.baselineCount})" }
            lines += "topPeriodChanges=$topChanges"
            lines += "nightChange=${fmt1(period.nightChange.deltaPercentPoint)}%p(recent=${period.nightChange.recentCount}, baseline=${period.nightChange.baselineCount})"
        }
        return lines
    }

    private fun buildDoNotStartWith(secondary: List<CounselingInsight>, phase: ConversationPhase): List<String> {
        val lines = mutableListOf<String>()
        lines += "분석 결과를 직접 설명하거나 '사진에서 보니'라고 말하지 않는다."
        if (phase == ConversationPhase.RAPPORT) {
            lines += "초반에는 생활 개선 팁, 루틴 처방, 목표 설정을 하지 않는다."
            lines += "secondary signal을 한꺼번에 묻지 않는다."
        }
        secondary.take(3).forEach { lines += "${it.domain}는 후속 대화에서만 사용한다." }
        return lines
    }

    private fun currentTaskFor(
        phase: ConversationPhase,
        safetyLevel: SafetyLevel,
        primary: List<CounselingInsight>
    ): String {
        if (safetyLevel == SafetyLevel.HIGH_SUPPORT || safetyLevel == SafetyLevel.IMMEDIATE_DANGER) {
            return "룰 기반 위기 응답을 사용한다. 자유 상담 프롬프트를 생성하지 않는다."
        }
        return when (phase) {
            ConversationPhase.SAFETY_CHECK -> "안전 여부를 부드럽게 확인한다. 직접 위험 발화가 없으면 위기 단정은 하지 않는다."
            ConversationPhase.RAPPORT -> {
                val topic = primary.firstOrNull()?.domain?.name ?: "GENERAL_CHECK_IN"
                "라포 형성을 우선한다. $topic 관련 부드러운 질문 1개만 하고, 조언이나 분석 설명은 하지 않는다."
            }
            ConversationPhase.PROBLEM_EXPLORATION -> "사용자의 이전 답변을 먼저 반영하고, primaryFocus 중 하나만 선택해 후속 질문 1개를 한다."
            ConversationPhase.PATTERN_REFLECTION -> "사용자의 말로 확인된 패턴만 요약하고, 맞는지 확인한다. 갤러리 분석을 근거처럼 말하지 않는다."
            ConversationPhase.MICRO_ACTION_PLAN -> "사용자가 원하면 부담이 가장 적은 생활 개선 행동 1개를 함께 정한다. 선택지를 2개 이하로 제시한다."
            ConversationPhase.FOLLOW_UP -> "지난 대화에서 정한 작은 행동이 실제로 가능했는지 비판 없이 확인한다."
        }
    }

    private fun phasePolicy(phase: ConversationPhase): String = when (phase) {
        ConversationPhase.SAFETY_CHECK -> "안전 확인 우선. 위험 단정 금지."
        ConversationPhase.RAPPORT -> "라포 형성 및 문제 입구 찾기. 조언 금지."
        ConversationPhase.PROBLEM_EXPLORATION -> "문제 상황 중심 탐색. 한 번에 한 도메인만."
        ConversationPhase.PATTERN_REFLECTION -> "사용자 발화 기반 패턴 반영. 분석 결과 직접 언급 금지."
        ConversationPhase.MICRO_ACTION_PLAN -> "동의 기반 작은 행동 계획 1개. 생활 개선은 공동 설계."
        ConversationPhase.FOLLOW_UP -> "시도 여부 확인 및 부담 조절. 실패를 비난하지 않음."
    }

    private fun defaultOpening(phase: ConversationPhase): String = when (phase) {
        ConversationPhase.RAPPORT -> "요즘 지내면서 가장 신경 쓰이는 일이나, 평소와 조금 달라졌다고 느끼는 부분이 있을까요?"
        ConversationPhase.PROBLEM_EXPLORATION -> "그 부분이 요즘 생활에서 가장 크게 영향을 주는 순간은 언제인가요?"
        ConversationPhase.PATTERN_REFLECTION -> "말해준 내용을 보면 이런 흐름이 있는 것 같은데, 맞게 느껴지나요?"
        ConversationPhase.MICRO_ACTION_PLAN -> "원한다면 지금 상황에서 부담이 가장 적은 작은 방법을 하나만 같이 정해볼까요?"
        ConversationPhase.FOLLOW_UP -> "지난번에 이야기한 작은 시도가 실제로는 어땠나요?"
        ConversationPhase.SAFETY_CHECK -> "지금 혼자 있거나 바로 위험한 상황은 아닌지 먼저 확인해도 될까요?"
    }

    private fun priorityWeight(priority: CounselingPriority): Int = when (priority) {
        CounselingPriority.HIGH -> 3
        CounselingPriority.MEDIUM -> 2
        CounselingPriority.LOW -> 1
    }

    private fun fmt(value: Double): String = String.format("%.2f", value)
    private fun fmt1(value: Double): String = String.format("%.1f", value)
}
