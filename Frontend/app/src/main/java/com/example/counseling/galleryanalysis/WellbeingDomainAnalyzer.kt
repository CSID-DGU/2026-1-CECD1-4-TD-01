package com.example.counseling.galleryanalysis

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v3.4.5 WHO-aligned counseling signal engine.
 *
 * 이번 패치의 핵심:
 * 1) Safety Overlay: 안전 시각 단서는 생활 패턴 도메인과 경쟁하지 않고 별도 우선 채널로 출력한다.
 * 2) 이미지 단독 안전 단서는 최대 MEDIUM_CHECK_IN까지만 허용하고, 자해/폭력/성폭력 확정은 금지한다.
 * 3) A 안정 기준선 과탐지를 줄이기 위해 PHYSICAL_ACTIVITY Active 임계값을 상향했다.
 * 4) SCHOOL_STRESS는 야간 신호가 없어도 충분한 학업 증가가 있으면 Active 후보가 될 수 있게 recall을 보강했다.
 * 5) RECOVERY_RESOURCE는 주요 문제 도메인이 있으면 후반부 회복자원 블록으로 내려간다.
 * 6) v3.4.5 False-positive guard: 비율 변화만으로 HIGH/Active가 뜨지 않도록
 *    실제 event count 변화, 주당 rate 변화, baseline expected recent count를 함께 확인한다.
 */
class WellbeingDomainAnalyzer(
    private val recentDays: Long = 14,
    private val baselineDays: Long = 30,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val dayMs = 24L * 60L * 60L * 1000L

    fun analyze(
        events: List<ActivityEvent>,
        preferences: List<PreferenceResult>,
        period: PeriodChangeAnalysis,
        quality: AnalysisQualityReport,
        visualRiskCues: List<VisualRiskCue>,
        safety: SafetyAssessment,
        phase: ConversationPhase = ConversationPhase.RAPPORT,
        auditExport: AuditExportResult? = null
    ): WellbeingAnalysisReport {
        val known = events.filter { it.category != ActivityCategory.UNKNOWN }
        val effectiveNow = chooseEffectiveNowMillis(known)
        val recentStart = effectiveNow - recentDays * dayMs
        val baselineStart = recentStart - baselineDays * dayMs
        val recent = known.filter { it.takenAtMillis in recentStart..effectiveNow }
        val baseline = known.filter { it.takenAtMillis >= baselineStart && it.takenAtMillis < recentStart }

        val metrics = WindowMetrics(recent, baseline)
        val domainScores = buildScores(metrics, preferences, visualRiskCues, quality)
        val protectiveResources = buildProtectiveResources(preferences, domainScores)
        val focusPlan = selectFocus(domainScores, safety, phase)
        val microActions = buildMicroActions(domainScores, focusPlan)
        val prompt = buildPrompt(
            phase = phase,
            focusPlan = focusPlan,
            domainScores = domainScores,
            protectiveResources = protectiveResources,
            microActions = microActions,
            safety = safety,
            quality = quality,
            auditExport = auditExport
        )
        return WellbeingAnalysisReport(
            domainScores = domainScores,
            primaryDomains = focusPlan.primary,
            secondaryDomains = focusPlan.secondary,
            protectiveResources = protectiveResources,
            microActionCandidates = microActions,
            conversationPhase = phase,
            suggestedOpening = focusPlan.suggestedOpening,
            doNotStartWith = focusPlan.doNotStartWith,
            safetyPolicyNote = "이미지 분석만으로 자살·자해·성폭력·폭력 피해를 확정하지 않는다. 직접 발화/질문지/명시적 위험 신호가 우선이다.",
            llmPrompt = prompt,
            auditExport = auditExport
        )
    }

    private fun buildScores(
        m: WindowMetrics,
        preferences: List<PreferenceResult>,
        visualRiskCues: List<VisualRiskCue>,
        quality: AnalysisQualityReport
    ): List<WellbeingDomainScore> {
        val scores = mutableListOf<WellbeingDomainScore>()

        val nightRecent = m.ratio(m.recentNight, m.recentTotal)
        val nightBaseline = m.ratio(m.baselineNight, m.baselineTotal)
        val nightDelta = nightRecent - nightBaseline
        val lateStudyRecent = m.ratio(m.recentLateStudy, m.recentTotal)
        val lateStudyBaseline = m.ratio(m.baselineLateStudy, m.baselineTotal)
        val lateStudyDelta = lateStudyRecent - lateStudyBaseline

        // SLEEP: absolute night count alone never makes HIGH. Direction must be positive.
        val sleepFromNightIncrease = if (nightDelta > 0.035 && m.recentNight >= 4) {
            scalePositive(nightDelta, med = 0.08, high = 0.18)
        } else 0.0
        val sleepFromLateStudy = if (lateStudyDelta > 0.045 && m.recentLateStudy >= 4) {
            0.85 * scalePositive(lateStudyDelta, med = 0.07, high = 0.16)
        } else 0.0
        val sleepScore = max(sleepFromNightIncrease, sleepFromLateStudy).coerceIn(0.0, 1.0)
        scores += score(
            domain = WellbeingDomain.SLEEP_AND_ROUTINE,
            rawScore = sleepScore,
            confidence = confidence(quality, m.recentTotal, labelAgreement = 0.78, crossSignal = if (lateStudyDelta > 0.045) 1.0 else 0.45),
            recentEvents = m.recentNight,
            baselineEvents = m.baselineNight,
            recentRatio = nightRecent,
            baselineRatio = nightBaseline,
            evidence = listOf(
                "최근 00~04시 event=${m.recentNight}, 기준=${m.baselineNight}",
                "야간 event 비율 변화=${fmtPct(nightDelta)}p",
                "야간 학업 proxy 변화=${fmtPct(lateStudyDelta)}p"
            ),
            counselingUse = "수면 자체를 단정하지 말고, 밤 시간대 생활 리듬 변화와 쉬는 시간 감소를 확인한다.",
            firstQuestion = "요즘 잠드는 시간이나 쉬는 시간이 평소와 달라졌다고 느끼나요?",
            microAction = "내일 기상 시간만 ±30분 범위로 고정해 보기"
        )

        val physicalRecent = m.domainRatio(WellbeingDomain.PHYSICAL_ACTIVITY, recent = true)
        val physicalBaseline = m.domainRatio(WellbeingDomain.PHYSICAL_ACTIVITY, recent = false)
        val physicalDelta = physicalRecent - physicalBaseline
        val physicalStrongDrop = m.meaningfulDecrease(
            recentCount = m.recentPhysical,
            baselineCount = m.baselinePhysical,
            ratioDelta = physicalDelta,
            minBaseline = 12,
            maxRecentToExpected = 0.45,
            minAbsRateDrop = 1.80,
            minRatioDrop = -0.18
        ) || (m.baselinePhysical >= 18 && m.recentPhysical <= 1 && physicalDelta <= -0.14)
        val physicalModerateDrop = m.meaningfulDecrease(
            recentCount = m.recentPhysical,
            baselineCount = m.baselinePhysical,
            ratioDelta = physicalDelta,
            minBaseline = 8,
            maxRecentToExpected = 0.60,
            minAbsRateDrop = 1.10,
            minRatioDrop = -0.12
        )
        val physicalScore = when {
            physicalStrongDrop -> max(0.74, scaleNegative(physicalDelta, med = -0.16, high = -0.30))
            physicalModerateDrop -> min(0.58, scaleNegative(physicalDelta, med = -0.12, high = -0.24))
            else -> 0.0
        }
        scores += score(
            domain = WellbeingDomain.PHYSICAL_ACTIVITY,
            rawScore = physicalScore,
            confidence = confidence(quality, m.recentTotal, labelAgreement = 0.82, crossSignal = if (m.recentSedentary > m.baselineSedentary) 1.0 else 0.55),
            recentEvents = m.recentPhysical,
            baselineEvents = m.baselinePhysical,
            recentRatio = physicalRecent,
            baselineRatio = physicalBaseline,
            evidence = listOf(
                "운동/외출 event 최근=${m.recentPhysical}, 기준=${m.baselinePhysical}",
                "운동/외출 비율 변화=${fmtPct(physicalDelta)}p"
            ),
            counselingUse = "활동 저하를 단정하지 않고 에너지, 외출, 몸을 움직이는 일이 줄었는지 확인한다.",
            firstQuestion = "요즘 밖에 나가거나 몸을 움직이는 일이 줄었다고 느끼나요?",
            microAction = "10분 걷기 또는 가벼운 스트레칭 1회"
        )

        val sedentaryRecent = m.domainRatio(WellbeingDomain.SEDENTARY_PATTERN, recent = true)
        val sedentaryBaseline = m.domainRatio(WellbeingDomain.SEDENTARY_PATTERN, recent = false)
        val sedentaryDelta = sedentaryRecent - sedentaryBaseline
        val sedentaryBase = if (sedentaryDelta > 0.06 && m.recentSedentary >= 4) scalePositive(sedentaryDelta, med = 0.13, high = 0.28) else 0.0
        val sedentaryScore = when {
            physicalScore >= 0.45 || m.socialDropScore >= 0.45 -> min(0.75, sedentaryBase)
            sedentaryBase >= 0.45 -> min(0.48, sedentaryBase) // 단독 primary 방지
            else -> sedentaryBase
        }
        scores += score(
            domain = WellbeingDomain.SEDENTARY_PATTERN,
            rawScore = sedentaryScore,
            confidence = confidence(quality, m.recentTotal, labelAgreement = 0.58, crossSignal = if (physicalScore >= 0.35 || m.socialDropScore >= 0.35) 1.0 else 0.35, ambiguityPenalty = 0.10),
            recentEvents = m.recentSedentary,
            baselineEvents = m.baselineSedentary,
            recentRatio = sedentaryRecent,
            baselineRatio = sedentaryBaseline,
            evidence = listOf(
                "실내/정적 event 최근=${m.recentSedentary}, 기준=${m.baselineSedentary}",
                "정적 패턴 비율 변화=${fmtPct(sedentaryDelta)}p",
                "단독 해석 금지: 활동/사회성 변화와 함께 있을 때만 사용"
            ),
            counselingUse = "방/실내 사진이 많다는 식으로 말하지 말고 생활 반경이 좁아졌는지 확인한다.",
            firstQuestion = "요즘 집이나 방 안에 머무는 시간이 평소보다 늘었다고 느끼나요?",
            microAction = "문 밖이나 집 근처까지 5분만 나가 보기"
        )

        val socialRecent = m.domainRatio(WellbeingDomain.SOCIAL_CONNECTION, recent = true)
        val socialBaseline = m.domainRatio(WellbeingDomain.SOCIAL_CONNECTION, recent = false)
        val socialDelta = socialRecent - socialBaseline
        val baseSocialConfidence = confidence(quality, m.recentTotal, labelAgreement = 0.72, crossSignal = if (m.recentMultiFace < m.baselineMultiFace) 1.0 else 0.50)
        val socialConfidence = if (m.baselineMultiFace == 0 && m.recentMultiFace == 0) min(0.78, baseSocialConfidence) else baseSocialConfidence
        scores += score(
            domain = WellbeingDomain.SOCIAL_CONNECTION,
            rawScore = m.socialDropScore,
            confidence = socialConfidence,
            recentEvents = m.recentSocial,
            baselineEvents = m.baselineSocial,
            recentRatio = socialRecent,
            baselineRatio = socialBaseline,
            evidence = listOf(
                "사회/인물 event 최근=${m.recentSocial}, 기준=${m.baselineSocial}",
                "사회적 연결 proxy 변화=${fmtPct(socialDelta)}p",
                "다인 얼굴 event 최근=${m.recentMultiFace}, 기준=${m.baselineMultiFace}"
            ),
            counselingUse = "관계 단절을 단정하지 말고 사람들과 보내는 시간/혼자 있는 시간의 변화를 확인한다.",
            firstQuestion = "최근 사람들과 보내는 시간이나 혼자 있는 시간이 예전과 달라졌다고 느끼나요?",
            microAction = "부담 없는 사람 한 명에게 짧은 안부 메시지 보내기"
        )

        val studyRecent = m.domainRatio(WellbeingDomain.SCHOOL_STRESS, recent = true)
        val studyBaseline = m.domainRatio(WellbeingDomain.SCHOOL_STRESS, recent = false)
        val studyDelta = studyRecent - studyBaseline
        val lateStudyRatio = if (m.recentStudy == 0) 0.0 else m.recentLateStudy.toDouble() / m.recentStudy
        val studyDeltaScore = if (studyDelta > 0.045 && m.recentStudy >= 5) scalePositive(studyDelta, med = 0.10, high = 0.24) else 0.0
        val studyStrongIncrease = m.meaningfulIncrease(
            recentCount = m.recentStudy,
            baselineCount = m.baselineStudy,
            ratioDelta = studyDelta,
            minRecent = 10,
            minCountDiff = 4,
            minAbsRateIncrease = 1.75,
            minRatioIncrease = 0.16
        )
        val studyModerateIncrease = m.meaningfulIncrease(
            recentCount = m.recentStudy,
            baselineCount = m.baselineStudy,
            ratioDelta = studyDelta,
            minRecent = 7,
            minCountDiff = 3,
            minAbsRateIncrease = 1.10,
            minRatioIncrease = 0.10
        )
        val schoolScore = when {
            studyDelta <= 0.0 -> 0.0
            m.recentStudy < 5 -> min(0.28, studyDeltaScore)
            (sleepScore >= 0.55 || lateStudyDelta > 0.045) && lateStudyRatio >= 0.25 && studyModerateIncrease -> max(0.72, studyDeltaScore)
            // v3.4.5: 야간 증가가 없어도 학업/과제 비율이 충분히 늘 수 있으나,
            // recent-baseline count 차이가 작으면 A 안정 기준선에서 false positive가 발생하므로 HIGH 금지.
            studyStrongIncrease -> max(0.62, min(0.76, studyDeltaScore))
            studyModerateIncrease -> max(0.45, min(0.58, studyDeltaScore))
            studyDelta >= 0.08 && m.recentStudy >= 6 -> min(0.38, studyDeltaScore)
            else -> min(0.30, studyDeltaScore)
        }
        scores += score(
            domain = WellbeingDomain.SCHOOL_STRESS,
            rawScore = schoolScore,
            confidence = confidence(quality, m.recentTotal, labelAgreement = 0.70, crossSignal = if (sleepScore >= 0.45 || lateStudyDelta > 0.045) 1.0 else 0.42),
            recentEvents = m.recentStudy,
            baselineEvents = m.baselineStudy,
            recentRatio = studyRecent,
            baselineRatio = studyBaseline,
            evidence = listOf(
                "학업/과제 event 최근=${m.recentStudy}, 기준=${m.baselineStudy}",
                "학업 비율 변화=${fmtPct(studyDelta)}p",
                "22시 이후 학업 event 비율=${fmtPct(lateStudyRatio)}"
            ),
            counselingUse = "책상/교재 이미지를 학업 스트레스로 단정하지 말고, 쉬는 시간 감소와 부담감을 확인한다.",
            firstQuestion = "요즘 공부나 과제가 평소보다 부담스럽게 느껴지나요?",
            microAction = "25분 집중 + 5분 휴식 1회만 시도하기"
        )

        val recoveryRecent = m.domainRatio(WellbeingDomain.RECOVERY_RESOURCE, recent = true)
        val recoveryBaseline = m.domainRatio(WellbeingDomain.RECOVERY_RESOURCE, recent = false)
        val recoveryDelta = recoveryRecent - recoveryBaseline
        val topResourceStrength = preferences.take(5).maxOfOrNull { it.recurrenceScore } ?: 0.0
        val recoveryStrongDrop = m.meaningfulDecrease(
            recentCount = m.recentRecovery,
            baselineCount = m.baselineRecovery,
            ratioDelta = recoveryDelta,
            minBaseline = 12,
            maxRecentToExpected = 0.42,
            minAbsRateDrop = 1.80,
            minRatioDrop = -0.18
        )
        val recoveryModerateDrop = m.meaningfulDecrease(
            recentCount = m.recentRecovery,
            baselineCount = m.baselineRecovery,
            ratioDelta = recoveryDelta,
            minBaseline = 8,
            maxRecentToExpected = 0.60,
            minAbsRateDrop = 1.10,
            minRatioDrop = -0.10
        )
        val recoveryScore = when {
            topResourceStrength < 0.42 -> 0.0
            recoveryStrongDrop -> max(0.70, scaleNegative(recoveryDelta, med = -0.14, high = -0.28))
            recoveryModerateDrop -> min(0.58, scaleNegative(recoveryDelta, med = -0.10, high = -0.22))
            else -> 0.0
        }
        scores += score(
            domain = WellbeingDomain.RECOVERY_RESOURCE,
            rawScore = recoveryScore,
            confidence = confidence(quality, m.recentTotal, labelAgreement = 0.68, crossSignal = if (topResourceStrength >= 0.45) 1.0 else 0.45),
            recentEvents = m.recentRecovery,
            baselineEvents = m.baselineRecovery,
            recentRatio = recoveryRecent,
            baselineRatio = recoveryBaseline,
            evidence = listOf(
                "회복 자원 후보 event 최근=${m.recentRecovery}, 기준=${m.baselineRecovery}",
                "회복 자원 비율 변화=${fmtPct(recoveryDelta)}p",
                "상위 반복 활동 recurrence=${fmt(topResourceStrength)}"
            ),
            counselingUse = "예전에 마음이 편해지던 활동이 최근에도 남아 있는지 확인한다.",
            firstQuestion = "예전에 마음이 조금 편해지던 활동이 요즘도 남아 있나요?",
            microAction = "예전에 하던 취미나 회복 활동을 10분만 다시 꺼내 보기"
        )

        val mealRecent = m.domainRatio(WellbeingDomain.MEAL_ROUTINE, recent = true)
        val mealBaseline = m.domainRatio(WellbeingDomain.MEAL_ROUTINE, recent = false)
        val mealDelta = mealRecent - mealBaseline
        val nightMealRatio = if (m.recentMeal == 0) 0.0 else m.recentNightMeal.toDouble() / m.recentMeal
        val mealSupport = m.recentMeal + m.baselineMeal
        val mealTimeShift = abs(mealDelta)
        val mealCountShift = abs(m.rateDelta(m.recentMeal, m.baselineMeal)) >= 1.20
        val mealScore = when {
            mealSupport < 8 -> 0.0
            nightMealRatio >= 0.35 && m.recentNightMeal >= 3 -> max(0.55, scalePositive(nightMealRatio, 0.35, 0.65))
            mealTimeShift >= 0.14 && mealCountShift && (physicalScore >= 0.45 || sedentaryScore >= 0.45 || sleepScore >= 0.45) -> min(0.62, scalePositive(mealTimeShift, 0.14, 0.28))
            mealTimeShift >= 0.22 && mealCountShift -> min(0.50, scalePositive(mealTimeShift, 0.22, 0.36))
            else -> 0.0
        }
        scores += score(
            domain = WellbeingDomain.MEAL_ROUTINE,
            rawScore = mealScore,
            confidence = confidence(quality, m.recentTotal, labelAgreement = 0.55, crossSignal = if (physicalScore >= 0.45 || sedentaryScore >= 0.45 || sleepScore >= 0.45) 0.85 else 0.30, ambiguityPenalty = 0.08),
            recentEvents = m.recentMeal,
            baselineEvents = m.baselineMeal,
            recentRatio = mealRecent,
            baselineRatio = mealBaseline,
            evidence = listOf(
                "식사/음식 event 최근=${m.recentMeal}, 기준=${m.baselineMeal}",
                "식사 proxy 변화=${fmtPct(mealDelta)}p",
                "야간 식사 event 비율=${fmtPct(nightMealRatio)}"
            ),
            counselingUse = "식사량을 단정하지 말고 식사 시간/일상 루틴 변화만 부드럽게 확인한다.",
            firstQuestion = "최근 식사 시간이나 하루 루틴이 평소보다 불규칙해졌다고 느끼나요?",
            microAction = "내일 한 끼만 일정한 시간에 먹기"
        )

        val injuryCue = visualRiskCues.firstOrNull { it.cueType == VisualRiskCueType.POSSIBLE_INJURY_CONTEXT }
        val safetyRaw = visualRiskCues.sumOf { it.confidence * min(4, it.imageCount).toDouble() / 3.0 }
        val safetyScore = when {
            injuryCue != null && injuryCue.imageCount >= 2 -> max(0.62, min(0.78, injuryCue.confidence + 0.12))
            injuryCue != null -> max(0.42, min(0.62, injuryCue.confidence))
            else -> min(0.42, safetyRaw)
        }
        scores += score(
            domain = WellbeingDomain.SAFETY_AND_VIOLENCE,
            rawScore = safetyScore,
            confidence = if (injuryCue != null) min(0.78, confidence(quality, m.recentTotal, labelAgreement = 0.62, crossSignal = 0.80, ambiguityPenalty = 0.05)) else min(0.54, confidence(quality, m.recentTotal, labelAgreement = 0.45, crossSignal = 0.25, ambiguityPenalty = 0.20)),
            recentEvents = visualRiskCues.sumOf { it.imageCount },
            baselineEvents = 0,
            recentRatio = 0.0,
            baselineRatio = 0.0,
            evidence = visualRiskCues.take(5).map { "${it.cueNameKo}: ${it.imageCount}장, conf=${fmt(it.confidence)}" }
                .ifEmpty { listOf("이미지 기반 안전 보조 단서 없음") },
            counselingUse = "이미지 단서만으로 위기 판단 금지. 자해/폭력으로 단정하지 않고 현재 몸과 마음의 안전을 확인한다.",
            firstQuestion = "요즘 몸이나 마음이 다치거나 위험하다고 느낀 적이 있나요?",
            microAction = "도움이 필요할 때 연락할 사람이나 기관을 하나 정해두기"
        )

        return scores.sortedWith(compareByDescending<WellbeingDomainScore> { it.score }.thenByDescending { it.confidence })
    }

    private fun score(
        domain: WellbeingDomain,
        rawScore: Double,
        confidence: Double,
        recentEvents: Int,
        baselineEvents: Int,
        recentRatio: Double,
        baselineRatio: Double,
        evidence: List<String>,
        counselingUse: String,
        firstQuestion: String,
        microAction: String
    ): WellbeingDomainScore {
        val score = rawScore.coerceIn(0.0, 1.0)
        val level = when {
            score >= 0.72 && confidence >= 0.48 -> WellbeingLevel.HIGH
            score >= 0.45 && confidence >= 0.38 -> WellbeingLevel.MEDIUM
            score >= 0.20 -> WellbeingLevel.LOW
            else -> WellbeingLevel.NONE
        }
        return WellbeingDomainScore(
            domain = domain,
            domainKo = domain.koName,
            level = level,
            score = score,
            confidence = confidence.coerceIn(0.0, 1.0),
            recentEvents = recentEvents,
            baselineEvents = baselineEvents,
            recentRatio = recentRatio,
            baselineRatio = baselineRatio,
            deltaPercentPoint = (recentRatio - baselineRatio) * 100.0,
            evidence = evidence,
            counselingUse = counselingUse,
            firstQuestion = firstQuestion,
            microActionCandidate = microAction,
            interpretationPolicy = "진단/판단 금지. 사용자에게는 갤러리 언급 없이 확인 질문으로만 사용."
        )
    }

    private fun buildProtectiveResources(
        preferences: List<PreferenceResult>,
        domainScores: List<WellbeingDomainScore>
    ): List<ProtectiveResource> {
        val recoveryDrop = domainScores.firstOrNull { it.domain == WellbeingDomain.RECOVERY_RESOURCE }?.score ?: 0.0
        return preferences
            .filter { it.recurrenceScore >= 0.35 && it.category in setOf(ActivityCategory.HOBBY_CREATIVE, ActivityCategory.PET_EMOTIONAL_RESOURCE, ActivityCategory.NATURE_OUTDOOR, ActivityCategory.PHYSICAL_ACTIVITY) }
            .sortedWith(compareByDescending<PreferenceResult> { it.recurrenceScore }.thenByDescending { it.activeWeekCount })
            .take(5)
            .map {
                ProtectiveResource(
                    resourceNameKo = it.categoryNameKo,
                    sourceCategory = it.category,
                    recurrenceScore = it.recurrenceScore,
                    activeWeekCount = it.activeWeekCount,
                    activeMonthCount = it.activeMonthCount,
                    observationSpanDays = it.observationSpanDays,
                    status = when {
                        recoveryDrop >= 0.65 -> "감소 가능성 확인 필요"
                        it.recurrenceScore >= 0.60 -> "유지되는 회복 자원 후보"
                        else -> "약한 회복 자원 후보"
                    },
                    counselingUse = "후반부에 생활 개선을 공동 설계할 때 회복 자원으로 활용 가능",
                    evidence = listOf("event=${it.eventCount}", "activeWeeks=${it.activeWeekCount}", "recurrence=${fmt(it.recurrenceScore)}")
                )
            }
    }

    private fun selectFocus(
        scores: List<WellbeingDomainScore>,
        safety: SafetyAssessment,
        phase: ConversationPhase
    ): FocusPlan {
        if (safety.level == SafetyLevel.HIGH_SUPPORT || safety.level == SafetyLevel.IMMEDIATE_DANGER || phase == ConversationPhase.SAFETY_CHECK) {
            val safetyScore = scores.firstOrNull { it.domain == WellbeingDomain.SAFETY_AND_VIOLENCE }
            return FocusPlan(
                primary = listOfNotNull(safetyScore),
                secondary = scores.filter { it.domain != WellbeingDomain.SAFETY_AND_VIOLENCE }.take(4),
                suggestedOpening = "지금 이 순간 혼자 버티기 어렵거나 바로 위험하다고 느끼나요?",
                doNotStartWith = baseDoNotStart() + listOf("위기 상황에서는 생활 패턴 조언을 먼저 하지 않는다.")
            )
        }

        // v3.4.4: 이미지 단독 안전 단서는 HIGH로 확정하지 않지만, MEDIUM이면 첫 질문으로 안전 확인을 우선한다.
        if (safety.level == SafetyLevel.MEDIUM_CHECK_IN) {
            val safetyScore = scores.firstOrNull { it.domain == WellbeingDomain.SAFETY_AND_VIOLENCE }
            return FocusPlan(
                primary = listOfNotNull(safetyScore),
                secondary = scores.filter { it.domain != WellbeingDomain.SAFETY_AND_VIOLENCE && it.level != WellbeingLevel.NONE }.take(4),
                suggestedOpening = "요즘 몸이나 마음이 다치거나 위험하다고 느낀 적이 있나요?",
                doNotStartWith = baseDoNotStart() + listOf("이미지 단서만으로 자해/폭력 피해를 확정하지 않는다.", "안전 확인 뒤에만 생활 패턴 이야기를 이어간다.")
            )
        }

        fun scoreOf(domain: WellbeingDomain): WellbeingDomainScore? = scores.firstOrNull { it.domain == domain }
        fun eligible(domain: WellbeingDomain, minScore: Double = 0.65): WellbeingDomainScore? {
            val s = scoreOf(domain) ?: return null
            return if (s.score >= minScore && s.confidence >= 0.45 && s.level in setOf(WellbeingLevel.MEDIUM, WellbeingLevel.HIGH)) s else null
        }

        val majorOrder = listOf(
            WellbeingDomain.SLEEP_AND_ROUTINE,
            WellbeingDomain.SOCIAL_CONNECTION,
            WellbeingDomain.PHYSICAL_ACTIVITY,
            WellbeingDomain.SCHOOL_STRESS,
            WellbeingDomain.MEAL_ROUTINE,
            WellbeingDomain.RECOVERY_RESOURCE
        )

        val active = majorOrder.asSequence()
            .mapNotNull { domain ->
                val minScore = when (domain) {
                    WellbeingDomain.PHYSICAL_ACTIVITY -> 0.74
                    WellbeingDomain.SOCIAL_CONNECTION -> 0.62
                    WellbeingDomain.SCHOOL_STRESS -> 0.62
                    WellbeingDomain.RECOVERY_RESOURCE -> 0.78
                    WellbeingDomain.MEAL_ROUTINE -> 0.72
                    else -> 0.65
                }
                eligible(domain, minScore)
            }
            .firstOrNull()

        val supporting = active?.let { activeScore ->
            val candidates = when (activeScore.domain) {
                WellbeingDomain.SLEEP_AND_ROUTINE -> listOf(WellbeingDomain.SCHOOL_STRESS, WellbeingDomain.MEAL_ROUTINE, WellbeingDomain.SEDENTARY_PATTERN)
                WellbeingDomain.SOCIAL_CONNECTION -> listOf(WellbeingDomain.SEDENTARY_PATTERN, WellbeingDomain.RECOVERY_RESOURCE, WellbeingDomain.SCHOOL_STRESS)
                WellbeingDomain.PHYSICAL_ACTIVITY -> listOf(WellbeingDomain.MEAL_ROUTINE, WellbeingDomain.SEDENTARY_PATTERN, WellbeingDomain.RECOVERY_RESOURCE)
                WellbeingDomain.SCHOOL_STRESS -> listOf(WellbeingDomain.SLEEP_AND_ROUTINE, WellbeingDomain.MEAL_ROUTINE, WellbeingDomain.RECOVERY_RESOURCE)
                WellbeingDomain.MEAL_ROUTINE -> listOf(WellbeingDomain.PHYSICAL_ACTIVITY, WellbeingDomain.SEDENTARY_PATTERN, WellbeingDomain.RECOVERY_RESOURCE)
                WellbeingDomain.RECOVERY_RESOURCE -> listOf(WellbeingDomain.PHYSICAL_ACTIVITY, WellbeingDomain.SOCIAL_CONNECTION, WellbeingDomain.SCHOOL_STRESS)
                else -> emptyList()
            }
            candidates.mapNotNull { scoreOf(it) }
                .firstOrNull { it.domain != activeScore.domain && it.score >= 0.45 && it.confidence >= 0.38 && it.level != WellbeingLevel.NONE }
        }

        val primary = listOfNotNull(active, supporting).take(2)
        val secondary = scores
            .filter { it !in primary && it.level != WellbeingLevel.NONE && it.domain != WellbeingDomain.SAFETY_AND_VIOLENCE }
            .sortedWith(compareByDescending<WellbeingDomainScore> { it.score }.thenByDescending { it.confidence })
            .take(6)

        val opening = active?.firstQuestion ?: "요즘 생활 리듬이나 마음 상태에서 조금 달라진 점이 있다면, 편한 만큼만 이야기해 줄래요?"
        return FocusPlan(
            primary = primary,
            secondary = secondary,
            suggestedOpening = opening,
            doNotStartWith = baseDoNotStart() + secondary.take(3).map { "${it.domainKo}${topicParticle(it.domainKo)} 사용자가 먼저 언급하거나 후속 탐색 단계에서만 사용한다." }
        )
    }

    private fun buildMicroActions(scores: List<WellbeingDomainScore>, focusPlan: FocusPlan): List<MicroActionCandidate> {
        val ordered = (focusPlan.primary + focusPlan.secondary + scores)
            .distinctBy { it.domain }
            .filter { it.level in setOf(WellbeingLevel.MEDIUM, WellbeingLevel.HIGH) && it.domain != WellbeingDomain.SAFETY_AND_VIOLENCE }
            .take(4)
        return ordered.map {
            MicroActionCandidate(
                domain = it.domain,
                domainKo = it.domainKo,
                action = it.microActionCandidate,
                whenToOffer = "사용자가 ${it.domainKo} 관련 어려움을 인정하고, 작은 시도를 함께 정해보고 싶다고 한 뒤",
                caution = "초반 라포 단계에서는 제안하지 않는다. 한 번에 행동 1개만 제안한다."
            )
        }
    }

    private fun buildPrompt(
        phase: ConversationPhase,
        focusPlan: FocusPlan,
        domainScores: List<WellbeingDomainScore>,
        protectiveResources: List<ProtectiveResource>,
        microActions: List<MicroActionCandidate>,
        safety: SafetyAssessment,
        quality: AnalysisQualityReport,
        auditExport: AuditExportResult?
    ): String = buildString {
        val active = focusPlan.primary.firstOrNull()
        val supporting = focusPlan.primary.drop(1)
        val secondary = focusPlan.secondary
        val resourceText = if (protectiveResources.isEmpty()) {
            "현재 대화에서 바로 사용할 만큼 반복성이 높은 선호/회복 자원은 충분하지 않음."
        } else {
            protectiveResources.take(3).joinToString("; ") {
                "${it.resourceNameKo}(recurrence=${fmt(it.recurrenceScore)}, activeWeeks=${it.activeWeekCount}, status=${it.status})"
            }
        }
        val qualityWarnings = buildQualityWarnings(quality)

        appendLine("[COUNSELING BRIEF - READ FIRST]")
        appendLine("- 이 입력은 진단 결과가 아니라 상담 진행 전략이다.")
        appendLine("- 지금 가장 먼저 다룰 주제: ${active?.domainKo ?: "일반 안부 확인"}")
        appendLine("- 지금 할 일: 라포 형성 + 사용자 경험 확인 질문 1개")
        appendLine("- 지금 하지 말 것: 분석 설명, 생활 개선 조언, 여러 주제 동시 질문")
        appendLine("- 후반부에 활용할 선호/회복 자원: $resourceText")
        appendLine()

        appendLine("[SAFETY POLICY]")
        appendLine("- 이미지 분석만으로 자살, 자해, 성폭력, 폭력 피해를 확정하지 않는다.")
        appendLine("- 직접 발화/질문지/명시적 위험 신호가 있으면 룰 기반 안전 응답이 LLM 자유 응답보다 우선한다.")
        appendLine("- currentSafetyLevel=${safety.level}, action=${safety.action}, ruleBased=${safety.mustUseRuleBasedResponse}")
        if (safety.level == SafetyLevel.MEDIUM_CHECK_IN || safety.level == SafetyLevel.LOW_OBSERVE) {
            appendLine("- safetyOverlay=true: 생활 패턴 도메인과 별개로 안전 확인을 고려한다.")
            appendLine("- imageOnlyLimit=자해/폭력/성폭력 확정 금지, HIGH_SUPPORT 금지. 현재 안전 여부만 확인한다.")
            safety.reasons.take(4).forEach { appendLine("- safetyEvidence=$it") }
        }
        appendLine()

        appendLine("[CONVERSATION PHASE]")
        appendLine("- currentPhase=$phase(${phase.koName})")
        appendLine("- phasePolicy=${phasePolicy(phase)}")
        appendLine()

        appendLine("[ACTIVE FOCUS - ASK NOW]")
        if (active == null) {
            appendLine("- GENERAL_CHECK_IN: 특별한 고우선도 변화 신호 없음. 일반 안부 확인부터 시작한다.")
            appendLine("  userQuestion=요즘 지내면서 가장 신경 쓰이는 일이나 달라진 생활 리듬이 있다면 편하게 이야기해 줄 수 있을까요?")
        } else {
            appendLine("- ${active.domain.name}(${active.domainKo}) / level=${active.level} / score=${fmt(active.score)} / conf=${fmt(active.confidence)}")
            appendLine("  why=${compactReason(active)}")
            appendLine("  useAs=${active.counselingUse}")
            appendLine("  userQuestion=${active.firstQuestion}")
        }
        appendLine()

        appendLine("[SUPPORTING FOCUS - DO NOT ASK YET]")
        if (supporting.isEmpty()) {
            appendLine("- none")
        } else {
            supporting.forEach {
                appendLine("- ${it.domain.name}(${it.domainKo}): ${it.level}, score=${fmt(it.score)}, conf=${fmt(it.confidence)}")
                appendLine("  useOnlyAfter=사용자가 active focus와 관련된 경험을 말한 뒤, 생활 맥락을 넓혀 확인할 때만 사용한다.")
                appendLine("  userQuestion=${it.firstQuestion}")
            }
        }
        appendLine()

        appendLine("[USE LATER - SECONDARY SIGNALS]")
        if (secondary.isEmpty()) {
            appendLine("- none")
        } else {
            secondary.take(4).forEach {
                appendLine("- ${it.domain.name}(${it.domainKo}): ${it.level}, conf=${fmt(it.confidence)}")
                appendLine("  useOnlyIf=사용자가 이 주제를 먼저 언급하거나 active/supporting focus 탐색 후 자연스럽게 연결될 때")
            }
        }
        appendLine()

        appendLine("[PREFERENCE / RECOVERY RESOURCES - USE AFTER RAPPORT]")
        if (protectiveResources.isEmpty()) {
            appendLine("- 충분히 반복적으로 확인된 선호/회복 자원 후보 없음. 억지로 취미를 제안하지 않는다.")
        } else {
            protectiveResources.take(4).forEach {
                appendLine("- ${it.resourceNameKo}: ${it.status}, recurrence=${fmt(it.recurrenceScore)}, activeWeeks=${it.activeWeekCount}, spanDays=${it.observationSpanDays}")
                appendLine("  howToAsk=${resourceQuestion(it)}")
                appendLine("  useTiming=사용자가 힘듦을 어느 정도 말한 뒤, 버팀목/회복 자원을 탐색할 때만 사용")
            }
        }
        appendLine()

        appendLine("[MICRO ACTION PLAN - FINAL PHASE ONLY]")
        appendLine("- 아래 행동 제안은 RAPPORT/PROBLEM_EXPLORATION 단계에서 말하지 않는다.")
        appendLine("- 사용자가 어려움을 인정하고 '작게 해볼 방법'을 원할 때만 1개 선택한다.")
        if (microActions.isEmpty()) {
            appendLine("- 아직 제안할 행동 없음. 먼저 대화를 듣는다.")
        } else {
            microActions.take(3).forEach {
                appendLine("- ${it.domain.name}(${it.domainKo}): ${it.action}")
                appendLine("  offerWhen=${it.whenToOffer}")
            }
        }
        appendLine()

        appendLine("[DO NOT START WITH]")
        focusPlan.doNotStartWith.forEach { appendLine("- $it") }
        appendLine("- 선호 활동을 초반부터 해결책처럼 제안하지 않는다. 먼저 사용자의 상태를 듣는다.")
        appendLine("- '너는 이 활동을 좋아하니까 이걸 해'처럼 단정하지 않는다.")
        appendLine()

        appendLine("[DATA QUALITY - INTERNAL]")
        appendLine("- classificationCoverage=${fmt(quality.classificationCoverage)}")
        appendLine("- unknownRatio=${fmt(quality.unknownRatio)}")
        appendLine("- averageConfidence=${fmt(quality.averageConfidence)}")
        appendLine("- auditExport=${auditExport?.directoryPath ?: "not saved"}")
        appendLine("- warning=${(quality.warnings + qualityWarnings).distinct().joinToString(" | ").ifBlank { "none" }}")
        appendLine()

        appendLine("[CURRENT TASK]")
        appendLine(currentTask(phase, safety, active))
        appendLine()

        appendLine("[SUGGESTED USER-FACING MESSAGE]")
        appendLine(focusPlan.suggestedOpening)
    }

    private fun compactReason(score: WellbeingDomainScore): String = score.evidence.take(2).joinToString("; ").ifBlank { "근거 부족. 질문으로만 확인." }

    private fun resourceQuestion(resource: ProtectiveResource): String = when (resource.sourceCategory) {
        ActivityCategory.PET_EMOTIONAL_RESOURCE -> "요즘 동물이나 반려동물과 관련된 시간이 조금이라도 마음을 편하게 해주는 편인가요?"
        ActivityCategory.HOBBY_CREATIVE -> "요즘에도 음악이나 만들기처럼 손이 가는 활동이 조금은 기분 전환에 도움이 되나요?"
        ActivityCategory.NATURE_OUTDOOR -> "밖에 나가거나 자연이 보이는 곳에 있는 시간이 조금이라도 숨 돌리는 데 도움이 되나요?"
        ActivityCategory.PHYSICAL_ACTIVITY -> "몸을 조금 움직이는 활동이 스트레스 조절에 도움이 되는 편인가요?"
        else -> "요즘 조금이라도 마음이 편해지는 활동이나 시간이 남아 있나요?"
    }

    private fun buildQualityWarnings(quality: AnalysisQualityReport): List<String> {
        val warnings = mutableListOf<String>()
        if (quality.averageConfidence < 0.60) warnings += "LOW_AVERAGE_CONFIDENCE: 결과를 단정하지 말고 질문 후보로만 사용"
        if (quality.classificationCoverage < 0.95) warnings += "COVERAGE_BELOW_TARGET: 일부 이미지가 UNKNOWN이므로 도메인 점수 과신 금지"
        if (quality.unknownRatio > 0.10) warnings += "UNKNOWN_RATIO_HIGH: 이미지 분류 불확실성 높음"
        if (quality.lowConfidenceRatio > 0.25) warnings += "LOW_CONFIDENCE_RATIO_HIGH: 저신뢰 이미지 비율 높음"
        return warnings
    }

    private fun phasePolicy(phase: ConversationPhase): String = when (phase) {
        ConversationPhase.SAFETY_CHECK -> "안전 확인 우선. 위험 단정 금지."
        ConversationPhase.RAPPORT -> "라포 형성. 분석 설명/조언 금지. 부드러운 질문 1개."
        ConversationPhase.PROBLEM_EXPLORATION -> "사용자 답변을 반영하고 primary domain 하나만 탐색."
        ConversationPhase.PATTERN_REFLECTION -> "사용자 발화 중심으로 패턴을 요약하고 맞는지 확인."
        ConversationPhase.MICRO_ACTION_PLAN -> "사용자 동의 후 5~15분 수준의 작은 행동 1개 공동 설계."
        ConversationPhase.FOLLOW_UP -> "시도 여부를 비판 없이 확인하고 부담을 조절."
    }

    private fun currentTask(phase: ConversationPhase, safety: SafetyAssessment, active: WellbeingDomainScore?): String {
        if (safety.mustUseRuleBasedResponse) return "룰 기반 위기 응답을 사용한다. 생활 패턴 조언을 먼저 하지 않는다."
        if (safety.level == SafetyLevel.MEDIUM_CHECK_IN) return "이미지 기반 보조 단서가 있으므로, 자해/폭력으로 단정하지 않고 현재 몸과 마음의 안전 여부를 부드럽게 확인한다."
        if (safety.level == SafetyLevel.LOW_OBSERVE) return "안전 보조 단서는 약하므로 일반 라포를 유지하되, 사용자가 힘듦을 말하면 안전/컨디션 확인으로 연결한다."
        return when (phase) {
            ConversationPhase.RAPPORT -> "라포 형성을 우선한다. ${active?.domainKo ?: "일반 안부"}에 대해 부드러운 질문 1개만 한다."
            ConversationPhase.PROBLEM_EXPLORATION -> "사용자의 답변을 반영하고, active focus 하나만 깊게 묻는다."
            ConversationPhase.PATTERN_REFLECTION -> "사용자 말 기반으로만 패턴을 요약하고 확인한다."
            ConversationPhase.MICRO_ACTION_PLAN -> "사용자가 원할 때만 작은 행동 1개를 제안한다."
            ConversationPhase.SAFETY_CHECK -> "현재 안전 여부를 먼저 확인한다."
            ConversationPhase.FOLLOW_UP -> "지난번 작은 시도가 어땠는지 확인한다."
        }
    }

    private fun baseDoNotStart(): List<String> = listOf(
        "'사진을 보니', '갤러리에서 보니'라는 표현을 쓰지 않는다.",
        "우울/불안/위험 상태를 단정하지 않는다.",
        "라포 단계에서 생활 개선 팁을 바로 말하지 않는다.",
        "한 턴에 여러 도메인을 한꺼번에 질문하지 않는다."
    )

    private fun topicParticle(text: String): String {
        val last = text.lastOrNull { it in '가'..'힣' } ?: return "는"
        val jong = (last.code - 0xAC00) % 28
        return if (jong == 0) "는" else "은"
    }

    private data class FocusPlan(
        val primary: List<WellbeingDomainScore>,
        val secondary: List<WellbeingDomainScore>,
        val suggestedOpening: String,
        val doNotStartWith: List<String>
    )

    private inner class WindowMetrics(
        val recent: List<ActivityEvent>,
        val baseline: List<ActivityEvent>
    ) {
        val recentTotal = recent.size
        val baselineTotal = baseline.size
        val recentNight = recent.count { isNight(it.takenAtMillis) }
        val baselineNight = baseline.count { isNight(it.takenAtMillis) }
        val recentLateStudy = recent.count { it.category == ActivityCategory.STUDY_TASK && isLateStudy(it.takenAtMillis) }
        val baselineLateStudy = baseline.count { it.category == ActivityCategory.STUDY_TASK && isLateStudy(it.takenAtMillis) }
        val recentStudy = recent.count { it.category == ActivityCategory.STUDY_TASK }
        val baselineStudy = baseline.count { it.category == ActivityCategory.STUDY_TASK }
        val recentPhysical = recent.count { it.category == ActivityCategory.PHYSICAL_ACTIVITY || it.category == ActivityCategory.NATURE_OUTDOOR }
        val baselinePhysical = baseline.count { it.category == ActivityCategory.PHYSICAL_ACTIVITY || it.category == ActivityCategory.NATURE_OUTDOOR }
        val recentSedentary = recent.count { it.category == ActivityCategory.INDOOR_STATIC }
        val baselineSedentary = baseline.count { it.category == ActivityCategory.INDOOR_STATIC }
        val recentSocial = recent.count { it.category == ActivityCategory.SOCIAL_ACTIVITY || it.labels.any { l -> l.text.lowercase().contains("group") || l.text.lowercase().contains("crowd") || l.text.lowercase().contains("people") } }
        val baselineSocial = baseline.count { it.category == ActivityCategory.SOCIAL_ACTIVITY || it.labels.any { l -> l.text.lowercase().contains("group") || l.text.lowercase().contains("crowd") || l.text.lowercase().contains("people") } }
        val recentMultiFace = recent.count { (it.labels.any { l -> l.text.lowercase().contains("person") || l.text.lowercase().contains("people") } && it.category == ActivityCategory.SOCIAL_ACTIVITY) || it.representativeEvidence.any { ev -> ev.value.contains("faceCount>=3") || ev.value.contains("faceCount=2") } }
        val baselineMultiFace = baseline.count { (it.labels.any { l -> l.text.lowercase().contains("person") || l.text.lowercase().contains("people") } && it.category == ActivityCategory.SOCIAL_ACTIVITY) || it.representativeEvidence.any { ev -> ev.value.contains("faceCount>=3") || ev.value.contains("faceCount=2") } }
        val recentRecovery = recent.count { it.category in setOf(ActivityCategory.HOBBY_CREATIVE, ActivityCategory.PET_EMOTIONAL_RESOURCE, ActivityCategory.NATURE_OUTDOOR, ActivityCategory.PHYSICAL_ACTIVITY) }
        val baselineRecovery = baseline.count { it.category in setOf(ActivityCategory.HOBBY_CREATIVE, ActivityCategory.PET_EMOTIONAL_RESOURCE, ActivityCategory.NATURE_OUTDOOR, ActivityCategory.PHYSICAL_ACTIVITY) }
        val recentMeal = recent.count { it.category == ActivityCategory.FOOD_MEAL }
        val baselineMeal = baseline.count { it.category == ActivityCategory.FOOD_MEAL }
        val recentNightMeal = recent.count { it.category == ActivityCategory.FOOD_MEAL && hour(it.takenAtMillis) >= 21 }
        private val recentWeeks = recentDays / 7.0
        private val baselineWeeks = baselineDays / 7.0

        val socialDropScore = if (baselineSocial >= 8 && meaningfulDecrease(
                recentCount = recentSocial,
                baselineCount = baselineSocial,
                ratioDelta = domainRatio(WellbeingDomain.SOCIAL_CONNECTION, true) - domainRatio(WellbeingDomain.SOCIAL_CONNECTION, false),
                minBaseline = 8,
                maxRecentToExpected = 0.55,
                minAbsRateDrop = 1.25,
                minRatioDrop = -0.12
            )) {
            scaleNegative(domainRatio(WellbeingDomain.SOCIAL_CONNECTION, true) - domainRatio(WellbeingDomain.SOCIAL_CONNECTION, false), -0.12, -0.26) * supportBoost(baselineSocial, recentSocial)
        } else 0.0

        fun ratio(count: Int, total: Int): Double = (count + 1.0) / (total + 5.0)

        fun baselineExpectedRecent(baselineCount: Int): Double = baselineCount * (recentDays.toDouble() / baselineDays.toDouble())

        fun rate(count: Int, recentWindow: Boolean): Double = count / (if (recentWindow) recentWeeks else baselineWeeks).coerceAtLeast(0.1)

        fun rateDelta(recentCount: Int, baselineCount: Int): Double = rate(recentCount, true) - rate(baselineCount, false)

        fun meaningfulIncrease(
            recentCount: Int,
            baselineCount: Int,
            ratioDelta: Double,
            minRecent: Int,
            minCountDiff: Int,
            minAbsRateIncrease: Double,
            minRatioIncrease: Double
        ): Boolean {
            if (recentCount < minRecent) return false
            if (recentCount - baselineCount < minCountDiff) return false
            if (ratioDelta < minRatioIncrease) return false
            if (rateDelta(recentCount, baselineCount) < minAbsRateIncrease) return false
            return true
        }

        fun meaningfulDecrease(
            recentCount: Int,
            baselineCount: Int,
            ratioDelta: Double,
            minBaseline: Int,
            maxRecentToExpected: Double,
            minAbsRateDrop: Double,
            minRatioDrop: Double
        ): Boolean {
            if (baselineCount < minBaseline) return false
            if (ratioDelta > minRatioDrop) return false
            val expected = baselineExpectedRecent(baselineCount)
            if (recentCount > expected * maxRecentToExpected) return false
            if (-rateDelta(recentCount, baselineCount) < minAbsRateDrop) return false
            return true
        }


        fun domainRatio(domain: WellbeingDomain, recent: Boolean): Double {
            val events = if (recent) this.recent else this.baseline
            val total = events.size
            val count = when (domain) {
                WellbeingDomain.PHYSICAL_ACTIVITY -> events.count { it.category == ActivityCategory.PHYSICAL_ACTIVITY || it.category == ActivityCategory.NATURE_OUTDOOR }
                WellbeingDomain.SEDENTARY_PATTERN -> events.count { it.category == ActivityCategory.INDOOR_STATIC }
                WellbeingDomain.SOCIAL_CONNECTION -> events.count { it.category == ActivityCategory.SOCIAL_ACTIVITY }
                WellbeingDomain.SCHOOL_STRESS -> events.count { it.category == ActivityCategory.STUDY_TASK }
                WellbeingDomain.RECOVERY_RESOURCE -> events.count { it.category in setOf(ActivityCategory.HOBBY_CREATIVE, ActivityCategory.PET_EMOTIONAL_RESOURCE, ActivityCategory.NATURE_OUTDOOR, ActivityCategory.PHYSICAL_ACTIVITY) }
                WellbeingDomain.MEAL_ROUTINE -> events.count { it.category == ActivityCategory.FOOD_MEAL }
                else -> 0
            }
            return ratio(count, total)
        }
    }

    private fun chooseEffectiveNowMillis(events: List<ActivityEvent>): Long {
        val now = System.currentTimeMillis()
        val latest = events.maxOfOrNull { it.takenAtMillis } ?: return now
        return if (latest < now - recentDays * dayMs) latest + dayMs else now
    }

    private fun isNight(millis: Long): Boolean = hour(millis) in 0..4
    private fun isLateStudy(millis: Long): Boolean = hour(millis) >= 22 || hour(millis) in 0..4
    private fun hour(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zoneId).hour

    private fun scalePositive(delta: Double, med: Double, high: Double): Double = when {
        delta <= 0 -> 0.0
        delta >= high -> 1.0
        delta <= med -> delta / med * 0.45
        else -> 0.45 + (delta - med) / (high - med).coerceAtLeast(0.0001) * 0.55
    }.coerceIn(0.0, 1.0)

    private fun scaleNegative(delta: Double, med: Double, high: Double): Double = when {
        delta >= 0 -> 0.0
        delta <= high -> 1.0
        delta >= med -> abs(delta / med) * 0.45
        else -> 0.45 + (abs(delta) - abs(med)) / (abs(high) - abs(med)).coerceAtLeast(0.0001) * 0.55
    }.coerceIn(0.0, 1.0)

    private fun supportBoost(baselineCount: Int, recentCount: Int): Double = when {
        baselineCount >= 5 -> 1.0
        baselineCount >= 3 -> 0.82
        baselineCount >= 1 && recentCount == 0 -> 0.55
        else -> 0.35
    }

    private fun confidence(
        quality: AnalysisQualityReport,
        recentEvents: Int,
        labelAgreement: Double,
        crossSignal: Double,
        ambiguityPenalty: Double = 0.0
    ): Double {
        val coverage = quality.classificationCoverage.coerceIn(0.0, 1.0)
        val sampleAdequacy = min(1.0, recentEvents / 12.0)
        return (0.35 * coverage +
            0.20 * sampleAdequacy +
            0.20 * labelAgreement.coerceIn(0.0, 1.0) +
            0.20 * crossSignal.coerceIn(0.0, 1.0) +
            0.05 * quality.averageConfidence.coerceIn(0.0, 1.0) -
            0.30 * ambiguityPenalty).coerceIn(0.0, 1.0)
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)
    private fun fmtPct(v: Double): String = String.format("%.1f", v * 100.0)
}
