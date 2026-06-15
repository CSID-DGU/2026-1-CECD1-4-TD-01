package com.example.counseling.galleryanalysis

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** 스캔 → ML Kit → 활동 분석 → 상담용 행동 점수/인사이트 → 안전성 평가까지 실행한다. */
class GalleryAnalysisEngine(
    private val context: Context,
    private val config: AnalysisConfig = AnalysisConfig(),
    private val scanner: GalleryScanner = GalleryScanner(context),
    private val thumbnailLoader: ThumbnailLoader = ThumbnailLoader(context),
    private val mlKitAnalyzer: MlKitImageAnalyzer = MlKitImageAnalyzer(),
    private val classifier: ActivityClassifier = ActivityClassifier(config.classifierMinConfidence),
    private val reviewer: SisyphusReviewPipeline = SisyphusReviewPipeline(config.reviewIterations),
    private val duplicateGrouper: DuplicateGrouper = DuplicateGrouper(),
    private val preferenceAnalyzer: PreferenceAnalyzer = PreferenceAnalyzer(),
    private val periodChangeAnalyzer: PeriodChangeAnalyzer = PeriodChangeAnalyzer(recentDays = config.recentDays, baselineDays = config.baselineDays),
    private val trendAnalyzer: TrendAnalyzer = TrendAnalyzer(),
    private val qualityEvaluator: QualityEvaluator = QualityEvaluator(),
    private val signalGenerator: SignalGenerator = SignalGenerator(),
    private val insightGenerator: InsightGenerator = InsightGenerator(),
    private val visualRiskCueDetector: VisualRiskCueDetector = VisualRiskCueDetector(),
    private val counselingFeatureMapper: CounselingFeatureMapper = CounselingFeatureMapper(),
    private val counselingInsightGenerator: CounselingInsightGenerator = CounselingInsightGenerator(),
    private val safetyDecisionEngine: SafetyDecisionEngine = SafetyDecisionEngine(),
    private val counselingContextBuilder: CounselingContextBuilder = CounselingContextBuilder(),
    private val wellbeingDomainAnalyzer: WellbeingDomainAnalyzer = WellbeingDomainAnalyzer(recentDays = config.recentDays, baselineDays = config.baselineDays),
    private val auditExporter: AuditExporter = AuditExporter(context)
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun analyze(progress: (percent: Int, message: String) -> Unit = { _, _ -> }): GalleryAnalysisResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        if (!GalleryPermissionHelper.hasGalleryImageAccess(context)) {
            val empty = GalleryAnalysisResult.empty()
            progress(100, "Gallery permission is not granted. Returning empty result.")
            Log.w(TAG, gson.toJson(empty))
            return@withContext empty
        }

        progress(2, "MediaStore 최근 ${config.longTermMonths}개월 이미지 스캔")
        val scanned = scanner.scanRecentImages(config.longTermMonths)
        progress(8, "스캔 완료: total=${scanned.size}")

        val filtered = scanned.map { it to ImageFilter.filter(it) }
        val analyzableAll = filtered.filter { it.second.isAnalyzable }.map { it.first }
        val targetImages = analyzableAll.take(config.maxImages)
        val excludedCount = filtered.count { !it.second.isAnalyzable }
        progress(12, "필터 완료: analyzable=${analyzableAll.size}, excluded=$excludedCount, target=${targetImages.size}")

        val classified = mutableListOf<ClassifiedImage>()
        if (targetImages.isEmpty()) {
            progress(70, "분석 가능한 이미지 없음")
        }

        targetImages.forEachIndexed { index, image ->
            coroutineContext.ensureActive()
            val percent = 12 + (((index + 1).toDouble() / targetImages.size.coerceAtLeast(1)) * 58.0).roundToInt()
            val item = analyzeOne(image)
            if (item != null) classified += item

            if (index == 0 || (index + 1) % config.progressEvery == 0 || index == targetImages.lastIndex) {
                val known = classified.count { it.category != ActivityCategory.UNKNOWN }
                val avgConf = classified.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.0
                progress(percent, "ML Kit 분석 ${index + 1}/${targetImages.size}, classified=$known, avgConf=${String.format("%.2f", avgConf)}")
            }
        }

        progress(74, "시시포스 검토 루프 시작: iterations=${config.reviewIterations}")
        val (reviewed, reviewReport) = reviewer.review(classified)
        progress(80, "시시포스 검토 완료: corrected=${reviewReport.totalCorrectedImages}")

        val events = duplicateGrouper.group(reviewed)
        progress(84, "activity event 묶기 완료: events=${events.size}")

        val preferences = preferenceAnalyzer.analyze(events)
        val period = periodChangeAnalyzer.analyze(events)
        val monthly = trendAnalyzer.monthly(events)
        val timeDistribution = trendAnalyzer.timeDistribution(events)
        val elapsed = System.currentTimeMillis() - started
        val baseQuality = qualityEvaluator.evaluate(
            totalImages = scanned.size,
            filtered = filtered,
            classified = reviewed,
            events = events,
            elapsedMs = elapsed
        )
        val postCorrectionRate = reviewReport.totalCorrectedImages.toDouble() / reviewed.size.coerceAtLeast(1).toDouble()
        val correctionWarnings = mutableListOf<String>()
        if (postCorrectionRate > 0.20) {
            correctionWarnings += "HIGH_CORRECTION_DEPENDENCY: postCorrectionRate=${String.format("%.2f", postCorrectionRate)}. 결과를 단정하지 말고 질문 후보로만 사용"
        }
        val quality = baseQuality.copy(warnings = (baseQuality.warnings + correctionWarnings).distinct())
        val signals = signalGenerator.generate(period, preferences, quality)
        val insights = insightGenerator.generate(preferences, period, quality)
        val visualRiskCues = visualRiskCueDetector.detect(reviewed)
        val behaviorScores = counselingFeatureMapper.map(period, preferences, quality, visualRiskCues)
        val counselingInsights = counselingInsightGenerator.generate(
            scores = behaviorScores,
            period = period,
            preferences = preferences,
            visualRiskCues = visualRiskCues,
            quality = quality
        )
        val safetyAssessment = safetyDecisionEngine.assess(
            behaviorScores = behaviorScores,
            visualRiskCues = visualRiskCues,
            userText = null
        )
        val provisionalSummary = AnalysisSummary(
            totalImages = scanned.size,
            analyzableImages = analyzableAll.size,
            excludedImages = excludedCount,
            analyzedImages = reviewed.size,
            eventCount = events.size,
            elapsedMs = elapsed
        )

        val provisionalWellbeing = wellbeingDomainAnalyzer.analyze(
            events = events,
            preferences = preferences,
            period = period,
            quality = quality,
            visualRiskCues = visualRiskCues,
            safety = safetyAssessment,
            phase = ConversationPhase.RAPPORT,
            auditExport = null
        )
        val auditExport = if (config.enableAuditExport) {
            auditExporter.export(
                filtered = filtered,
                classified = reviewed,
                events = events,
                wellbeingScores = provisionalWellbeing.domainScores,
                quality = quality,
                summary = provisionalSummary
            )
        } else {
            null
        }
        val wellbeingReport = wellbeingDomainAnalyzer.analyze(
            events = events,
            preferences = preferences,
            period = period,
            quality = quality,
            visualRiskCues = visualRiskCues,
            safety = safetyAssessment,
            phase = ConversationPhase.RAPPORT,
            auditExport = auditExport
        )

        // v3.4부터 LLM에는 raw activity report가 아니라 WHO-aligned wellbeing prompt를 우선 전달한다.
        val llmContextSummary = wellbeingReport.llmPrompt
        val counselingReport = CounselingAnalysisReport(
            behaviorScores = behaviorScores,
            counselingInsights = counselingInsights,
            visualRiskCues = visualRiskCues,
            safetyAssessment = safetyAssessment,
            llmContextSummary = llmContextSummary,
            wellbeingReport = wellbeingReport
        )

        val result = GalleryAnalysisResult(
            analysisPeriod = AnalysisPeriod(
                longTermMonths = config.longTermMonths,
                recentDays = config.recentDays.toInt(),
                baselineDays = config.baselineDays.toInt()
            ),
            summary = provisionalSummary,
            qualityReport = quality,
            sisyphusReview = reviewReport,
            preferences = preferences,
            periodChanges = period.categoryChanges,
            nightChange = period.nightChange,
            timeDistribution = timeDistribution,
            monthlyTrends = monthly,
            insightCards = insights,
            signals = signals,
            counselingReport = counselingReport
        )

        progress(100, "WHO 도메인 상담 분석 완료: events=${events.size}, safety=${safetyAssessment.level}, primary=${wellbeingReport.primaryDomains.joinToString { it.domain.name }}")
        Log.d(TAG, "Final GalleryAnalysisResult JSON:\n${gson.toJson(result)}")
        return@withContext result
    }

    private suspend fun analyzeOne(image: GalleryImage): ClassifiedImage? {
        val bitmap = thumbnailLoader.loadThumbnail(image.uri, config.thumbnailLongSide) ?: return null
        return try {
            val hash = runCatching { ImageHasher.averageHash(bitmap) }.getOrNull()
            val output = mlKitAnalyzer.analyze(bitmap)
            classifier.classify(image, output, hash)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to analyze imageId=${image.imageId}: ${t.message}")
            classifier.classify(image, ImageAnalysisOutput.failed(t.message), null)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun toPrettyJson(result: GalleryAnalysisResult): String = gson.toJson(result)

    fun assessUserTextSafety(userText: String, lastReport: CounselingAnalysisReport? = null): SafetyAssessment {
        val scores = lastReport?.behaviorScores ?: BehaviorScores(
            activityActivationScore = 0.0,
            lifeRhythmInstabilityScore = 0.0,
            socialWithdrawalSignalScore = 0.0,
            academicLoadSignalScore = 0.0,
            interestContinuityDropScore = 0.0,
            mealPatternChangeScore = 0.0,
            visualRiskCueScore = 0.0,
            overallCounselingPriorityScore = 0.0
        )
        val cues = lastReport?.visualRiskCues.orEmpty()
        return safetyDecisionEngine.assess(scores, cues, userText)
    }

    companion object {
        const val TAG = "GalleryAnalysis"
    }
}
