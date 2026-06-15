package com.example.galleryanalysis

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startAnalysis() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        startButton.setOnClickListener {
            if (GalleryPermissionHelper.hasGalleryImageAccess(this)) {
                startAnalysis()
            } else {
                permissionLauncher.launch(GalleryPermissionHelper.requiredPermissions())
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val title = TextView(this).apply {
            text = "갤러리 상담 분석 v3.4.3 도메인 게이트 패치"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        statusText = TextView(this).apply {
            text = "분석 전입니다. 버튼을 누르면 MediaStore + ML Kit + WHO 도메인 게이트/선호 자원/단계별 프롬프트/감사 로그를 실행합니다."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        startButton = Button(this).apply {
            text = "갤러리 분석 시작"
            setPadding(0, 16, 0, 16)
        }
        resultText = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            text = "결과가 여기에 표시됩니다. 전체 JSON은 Logcat tag: GalleryAnalysis 에 출력됩니다."
        }
        val scroll = ScrollView(this).apply {
            addView(resultText)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(progressBar)
        root.addView(startButton)
        root.addView(scroll)
        setContentView(root)
    }

    private fun startAnalysis() {
        startButton.isEnabled = false
        progressBar.progress = 0
        resultText.text = "분석 중입니다...\nLogcat에서 GalleryAnalysis 태그를 확인하세요."

        lifecycleScope.launch {
            val engine = GalleryAnalysisEngine(
                context = applicationContext,
                config = AnalysisConfig(
                    thumbnailLongSide = 384,
                    reviewIterations = 3,
                    maxImages = Int.MAX_VALUE
                )
            )
            val result = engine.analyze { percent, message ->
                runOnUiThread {
                    progressBar.progress = percent
                    statusText.text = "$percent% - $message"
                    Log.d(GalleryAnalysisEngine.TAG, "$percent% - $message")
                }
            }
            runOnUiThread {
                startButton.isEnabled = true
                resultText.text = formatForScreen(result)
            }
        }
    }

    private fun formatForScreen(result: GalleryAnalysisResult): String {
        val sb = StringBuilder()
        val report = result.counselingReport
        val wellbeing = report.wellbeingReport
        val correctionRate = result.sisyphusReview.totalCorrectedImages.toDouble() / result.summary.analyzedImages.coerceAtLeast(1).toDouble()

        sb.appendLine("요약")
        sb.appendLine("총 사진: ${result.summary.totalImages}")
        sb.appendLine("분석 대상: ${result.summary.analyzableImages}")
        sb.appendLine("실제 분석 완료: ${result.summary.analyzedImages}")
        sb.appendLine("제외: ${result.summary.excludedImages}")
        sb.appendLine("활동 event: ${result.summary.eventCount}")
        sb.appendLine("소요 시간: ${result.summary.elapsedMs / 1000.0}s")
        sb.appendLine()

        sb.appendLine("분석 품질")
        sb.appendLine("ML Kit 성공률: ${(result.qualityReport.mlKitSuccessRate * 100).fmt()}%")
        sb.appendLine("분류 커버리지: ${(result.qualityReport.classificationCoverage * 100).fmt()}%")
        sb.appendLine("UNKNOWN 비율: ${(result.qualityReport.unknownRatio * 100).fmt()}%")
        sb.appendLine("평균 신뢰도: ${result.qualityReport.averageConfidence.fmt2()}")
        sb.appendLine("시시포스 보정: ${result.sisyphusReview.totalCorrectedImages}장 (${(correctionRate * 100).fmt()}%)")
        val qualityWarnings = mutableListOf<String>()
        qualityWarnings += result.qualityReport.warnings
        if (result.qualityReport.averageConfidence < 0.60) qualityWarnings += "평균 신뢰도 낮음: 결과를 단정하지 말고 질문 후보로만 사용"
        if (correctionRate > 0.20) qualityWarnings += "보정 의존도 높음: 매핑 규칙/라벨 품질 재검토 필요"
        if (result.qualityReport.classificationCoverage < 0.95) qualityWarnings += "분류 커버리지 목표 미달: 일부 도메인 점수 과신 금지"
        if (qualityWarnings.isEmpty()) sb.appendLine("주의: 없음") else qualityWarnings.distinct().forEach { sb.appendLine("주의: $it") }
        sb.appendLine()

        if (wellbeing == null) {
            sb.appendLine("상담 활용 요약")
            sb.appendLine("WHO 도메인 리포트 없음. 일반 안부 확인으로 시작하세요.")
        } else {
            sb.appendLine("상담 활용 요약")
            sb.appendLine("대화 단계: ${wellbeing.conversationPhase.koName}")
            sb.appendLine("지금 AI가 할 일: ${if (wellbeing.primaryDomains.isEmpty()) "일반 안부 확인" else wellbeing.primaryDomains.first().domainKo + "에 대해 부드럽게 1문장 질문"}")
            sb.appendLine("추천 시작 질문: ${wellbeing.suggestedOpening}")
            sb.appendLine("초반 금지: 분석 설명, 갤러리 언급, 생활 개선 조언, 여러 주제 동시 질문")
            sb.appendLine()

            sb.appendLine("지금 바로 사용할 Active Focus")
            val activeFocus = wellbeing.primaryDomains.firstOrNull()
            if (activeFocus == null) {
                sb.appendLine("- GENERAL_CHECK_IN: 고우선도 변화 신호 없음")
            } else {
                sb.appendLine("- ${activeFocus.domainKo}: ${activeFocus.level}, score=${activeFocus.score.fmt2()}, conf=${activeFocus.confidence.fmt2()}")
                activeFocus.evidence.take(2).forEach { sb.appendLine("   · $it") }
                sb.appendLine("   상담 사용: ${activeFocus.counselingUse}")
                sb.appendLine("   질문: ${activeFocus.firstQuestion}")
            }
            sb.appendLine()

            sb.appendLine("보조 Primary Focus - 지금 바로 묻지 않음")
            val supportingFocus = wellbeing.primaryDomains.drop(1)
            if (supportingFocus.isEmpty()) {
                sb.appendLine("- 없음")
            } else {
                supportingFocus.forEach { domain ->
                    sb.appendLine("- ${domain.domainKo}: ${domain.level}, score=${domain.score.fmt2()}, conf=${domain.confidence.fmt2()}")
                    domain.evidence.take(2).forEach { sb.appendLine("   · $it") }
                    sb.appendLine("   사용 조건: active focus 탐색 후 자연스럽게 생활 맥락을 넓힐 때만 사용")
                    sb.appendLine("   질문 후보: ${domain.firstQuestion}")
                }
            }
            sb.appendLine()

            sb.appendLine("나중에만 사용할 Secondary Signals")
            if (wellbeing.secondaryDomains.isEmpty()) {
                sb.appendLine("- 없음")
            } else {
                wellbeing.secondaryDomains.take(4).forEach { domain ->
                    sb.appendLine("- ${domain.domainKo}: ${domain.level}, score=${domain.score.fmt2()}, conf=${domain.confidence.fmt2()}")
                    sb.appendLine("  조건: 사용자가 먼저 관련 경험을 말하거나 primary 탐색 후 자연스럽게 연결될 때")
                }
            }
            sb.appendLine()

            sb.appendLine("선호/회복 자원 후보 - 라포 이후 활용")
            if (wellbeing.protectiveResources.isEmpty()) {
                sb.appendLine("- 충분히 반복적으로 확인된 후보 없음")
            } else {
                wellbeing.protectiveResources.take(5).forEach { resource ->
                    sb.appendLine("- ${resource.resourceNameKo}: ${resource.status}, recurrence=${resource.recurrenceScore.fmt2()}, activeWeeks=${resource.activeWeekCount}, spanDays=${resource.observationSpanDays}")
                    sb.appendLine("  사용법: 힘든 점을 어느 정도 들은 뒤 '이 활동이 조금이라도 도움이 되는지' 확인하는 질문으로만 사용")
                }
            }
            sb.appendLine()

            sb.appendLine("생활 패턴 개선 후보 - 상담 후반부에만 사용")
            if (wellbeing.microActionCandidates.isEmpty()) {
                sb.appendLine("- 아직 제안하지 않음")
            } else {
                wellbeing.microActionCandidates.take(4).forEach { action ->
                    sb.appendLine("- ${action.domainKo}: ${action.action}")
                    sb.appendLine("  조건: ${action.whenToOffer}")
                }
            }
            sb.appendLine()

            wellbeing.auditExport?.let { audit ->
                sb.appendLine("감사 로그")
                sb.appendLine("- 저장됨: ${audit.saved}")
                sb.appendLine("- 경로: ${audit.directoryPath ?: audit.warning.orEmpty()}")
                sb.appendLine()
            }
        }

        sb.appendLine("AI 상담 프롬프트")
        sb.appendLine("아래 블록은 LLM/상담 챗봇 입력용입니다. 화면 위 요약은 사람이 읽기 위한 버전입니다.")
        sb.appendLine("----- AI_PROMPT_BEGIN -----")
        sb.appendLine(report.llmContextSummary.trim())
        sb.appendLine("----- AI_PROMPT_END -----")
        sb.appendLine()

        sb.appendLine("디버그용 상세: 활동/기간 변화")
        sb.appendLine("장기 반복 활동 후보")
        result.preferences.take(8).forEachIndexed { index, pref ->
            val subs = pref.topSubActivities.joinToString { "${it.name}(${(it.ratioWithinCategory * 100).fmt()}%)" }
            sb.appendLine("${index + 1}. ${pref.category.icon} ${pref.categoryNameKo}: event ${pref.eventCount}, photo ${pref.photoCount}, ratio ${(pref.ratio * 100).fmt()}%, recurrence ${pref.recurrenceScore.fmt2()}, conf ${pref.confidence.fmt2()}")
            sb.appendLine("   반복성: activeWeeks=${pref.activeWeekCount}, activeMonths=${pref.activeMonthCount}, spanDays=${pref.observationSpanDays}")
            if (subs.isNotBlank()) sb.appendLine("   세부 후보: $subs")
        }
        sb.appendLine()

        sb.appendLine("기간 변화 TOP")
        result.periodChanges.take(6).forEach { change ->
            sb.appendLine("${change.category.icon} ${change.categoryNameKo}: 최근 ${(change.recentRatio * 100).fmt()}%, 기준 ${(change.baselineRatio * 100).fmt()}%, 변화 ${change.deltaPercentPoint.fmt()}%p (${change.direction})")
        }
        sb.appendLine("야간 촬영 변화: ${result.nightChange.deltaPercentPoint.fmt()}%p")
        sb.appendLine()

        sb.appendLine("디버그용 상세: 기존 상담 인사이트/신호")
        sb.appendLine("기존 상담용 행동 점수")
        sb.appendLine("생활 리듬 불안정: ${report.behaviorScores.lifeRhythmInstabilityScore.fmt2()}")
        sb.appendLine("사회적 연결 변화: ${report.behaviorScores.socialWithdrawalSignalScore.fmt2()}")
        sb.appendLine("학업 부담 신호: ${report.behaviorScores.academicLoadSignalScore.fmt2()}")
        sb.appendLine("관심사 지속성 저하: ${report.behaviorScores.interestContinuityDropScore.fmt2()}")
        sb.appendLine("이미지 보조 위험 단서: ${report.behaviorScores.visualRiskCueScore.fmt2()}")
        sb.appendLine("상담 우선도: ${report.behaviorScores.overallCounselingPriorityScore.fmt2()}")
        sb.appendLine()

        sb.appendLine("안전성 평가")
        sb.appendLine("level=${report.safetyAssessment.level}, action=${report.safetyAssessment.action}, ruleBased=${report.safetyAssessment.mustUseRuleBasedResponse}")
        sb.appendLine(report.safetyAssessment.responseGuideKo)
        report.safetyAssessment.reasons.take(4).forEach { sb.appendLine("- $it") }
        sb.appendLine()

        sb.appendLine("이미지 기반 안전 보조 단서")
        if (report.visualRiskCues.isEmpty()) {
            sb.appendLine("- 감지된 보조 단서 없음")
        } else {
            report.visualRiskCues.forEach { cue ->
                sb.appendLine("- ${cue.cueNameKo}: ${cue.imageCount}장, conf=${cue.confidence.fmt2()}")
            }
        }
        sb.appendLine()

        sb.appendLine("레거시 상담 인사이트")
        report.counselingInsights.take(6).forEach { insight ->
            sb.appendLine("- ${insight.domainKo} / ${insight.priority} / conf=${insight.confidence.fmt2()}")
            sb.appendLine("  ${insight.safeInterpretation}")
        }
        sb.appendLine()

        sb.appendLine("신호")
        result.signals.forEach { signal ->
            sb.appendLine("- ${signal.signalType} / ${signal.strength} / conf=${signal.confidence.fmt2()}")
            sb.appendLine("  ${signal.explanation}")
        }
        return sb.toString()
    }

}

private fun Double.fmt(): String = String.format("%.1f", this)
private fun Double.fmt2(): String = String.format("%.2f", this)
