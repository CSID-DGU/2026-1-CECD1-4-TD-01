package com.example.galleryanalysis

import android.net.Uri

/**
 * MediaStore에서 읽은 원본 메타데이터.
 * 원본 이미지 바이트는 보관하지 않고 uri/메타데이터만 메모리에 들고 간다.
 */
data class GalleryImage(
    val imageId: Long,
    val uri: Uri,
    val fileName: String?,
    val relativePath: String?,
    val mimeType: String?,
    val dateTakenMillis: Long?,
    val dateAddedMillis: Long?,
    val inferredTakenAtMillis: Long? = null,
    val width: Int?,
    val height: Int?
) {
    /**
     * Android MediaStore가 adb push 직후 DATE_TAKEN을 복사 시점으로 잡는 경우가 있다.
     * 테스트 데이터셋과 일반 카메라 파일 모두 EXIF/파일명 기반 날짜가 더 안정적이면 그것을 우선한다.
     */
    val bestTakenAtMillis: Long?
        get() = inferredTakenAtMillis ?: dateTakenMillis ?: dateAddedMillis
}

enum class ImageFilterStatus {
    ANALYZABLE,
    EXCLUDED_SCREENSHOT,
    EXCLUDED_DOWNLOAD,
    EXCLUDED_MESSENGER,
    EXCLUDED_EDITED,
    EXCLUDED_LOW_QUALITY,
    UNKNOWN
}

data class FilterResult(
    val status: ImageFilterStatus,
    val reason: String? = null
) {
    val isAnalyzable: Boolean get() = status == ImageFilterStatus.ANALYZABLE
}

data class VisionLabel(
    val text: String,
    val confidence: Float
)

data class VisionObject(
    val category: String,
    val confidence: Float
)

enum class SocialContextType {
    NONE,
    SINGLE_FACE,
    MULTI_FACE,
    PEOPLE_OR_CROWD_LABEL,
    PUBLIC_PLACE_LABEL
}

data class ImageAnalysisOutput(
    val labels: List<VisionLabel>,
    val objects: List<VisionObject>,
    val faceCount: Int,
    val socialContextType: SocialContextType,
    val mlKitSucceeded: Boolean = true,
    val errorMessage: String? = null
) {
    companion object {
        fun failed(message: String?): ImageAnalysisOutput = ImageAnalysisOutput(
            labels = emptyList(),
            objects = emptyList(),
            faceCount = 0,
            socialContextType = SocialContextType.NONE,
            mlKitSucceeded = false,
            errorMessage = message
        )
    }
}

enum class ActivityCategory(val koName: String, val icon: String) {
    STUDY_TASK("학업/과제", "📘"),
    PHYSICAL_ACTIVITY("운동/신체활동", "🏃"),
    SOCIAL_ACTIVITY("사회활동", "👥"),
    HOBBY_CREATIVE("취미/창작", "🎨"),
    FOOD_MEAL("음식/식사", "🍽"),
    PET_EMOTIONAL_RESOURCE("반려동물/정서자원", "🐾"),
    NATURE_OUTDOOR("자연/외출", "🌳"),
    INDOOR_STATIC("실내/정적활동", "🏠"),
    UNKNOWN("알 수 없음", "❔")
}

enum class EvidenceSource {
    ML_LABEL,
    ML_OBJECT,
    FACE_COUNT,
    FILENAME_OR_FOLDER,
    TEMPORAL_CONTEXT,
    REVIEW_CORRECTION
}

data class CategoryEvidence(
    val source: EvidenceSource,
    val value: String,
    val weight: Double
)

data class CategoryScore(
    val category: ActivityCategory,
    val rawScore: Double,
    val normalizedScore: Double,
    val evidence: List<CategoryEvidence>
)

data class ClassifiedImage(
    val image: GalleryImage,
    val category: ActivityCategory,
    val confidence: Float,
    val labels: List<VisionLabel>,
    val objects: List<VisionObject>,
    val faceCount: Int,
    val socialContextType: SocialContextType,
    val averageHash: Long?,
    val scoreBreakdown: List<CategoryScore>,
    val subActivities: List<String>,
    val mlKitSucceeded: Boolean,
    val reviewNotes: List<String> = emptyList()
) {
    val isKnown: Boolean get() = category != ActivityCategory.UNKNOWN
    val isLowConfidence: Boolean get() = confidence < 0.48f
}

data class ActivityEvent(
    val eventId: String,
    val representativeImageId: Long,
    val photoCount: Int,
    val category: ActivityCategory,
    val takenAtMillis: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val labels: List<VisionLabel>,
    val subActivities: List<String>,
    val confidence: Float,
    val representativeEvidence: List<CategoryEvidence>
)

data class LabelAggregate(
    val label: String,
    val count: Int,
    val averageConfidence: Float
)

data class SubActivityAggregate(
    val name: String,
    val count: Int,
    val ratioWithinCategory: Double
)

data class PreferenceResult(
    val category: ActivityCategory,
    val categoryNameKo: String,
    val eventCount: Int,
    val photoCount: Int,
    val ratio: Double,
    val confidence: Double,
    /**
     * 선호도 후보의 핵심 점수. 단순 사진 수가 아니라
     * event 반복성 + 활동 주기성 + 여러 주/월에 걸친 지속성을 함께 반영한다.
     */
    val recurrenceScore: Double = 0.0,
    val activeWeekCount: Int = 0,
    val activeMonthCount: Int = 0,
    val observationSpanDays: Int = 0,
    val averagePhotosPerEvent: Double = 0.0,
    val topLabels: List<LabelAggregate>,
    val topSubActivities: List<SubActivityAggregate>,
    val interpretation: String
)

data class PeriodChangeResult(
    val category: ActivityCategory,
    val categoryNameKo: String,
    val recentCount: Int,
    val baselineCount: Int,
    val recentRatio: Double,
    val baselineRatio: Double,
    val deltaPercentPoint: Double,
    val direction: String
)

data class NightChangeResult(
    val recentCount: Int,
    val baselineCount: Int,
    val recentRatio: Double,
    val baselineRatio: Double,
    val deltaPercentPoint: Double
)

data class PeriodChangeAnalysis(
    val categoryChanges: List<PeriodChangeResult>,
    val nightChange: NightChangeResult
)

enum class SignalType {
    STUDY_LOAD_INCREASED,
    NIGHT_ACTIVITY_INCREASED,
    PHYSICAL_ACTIVITY_DECREASED,
    SOCIAL_ACTIVITY_DECREASED,
    FOOD_PATTERN_CHANGED,
    INTEREST_ACTIVITY_DECREASED,
    LIFE_PATTERN_SHIFT_DETECTED,
    ANALYSIS_CONFIDENCE_LOW
}

enum class SignalStrength { LOW, MEDIUM, HIGH }

data class SignalResult(
    val signalType: SignalType,
    val strength: SignalStrength,
    val confidence: Double,
    val recommendedQuestionDomain: List<String>,
    val explanation: String
)

data class AnalysisPeriod(
    val longTermMonths: Int = 6,
    val recentDays: Int = 14,
    val baselineDays: Int = 30
)

data class AnalysisSummary(
    val totalImages: Int,
    val analyzableImages: Int,
    val excludedImages: Int,
    val analyzedImages: Int,
    val eventCount: Int,
    val elapsedMs: Long
)

data class ReviewIterationReport(
    val iteration: Int,
    val beforeUnknownRatio: Double,
    val afterUnknownRatio: Double,
    val correctedImages: Int,
    val notes: List<String>
)

data class SisyphusReviewReport(
    val iterations: List<ReviewIterationReport>,
    val totalCorrectedImages: Int
)

data class AnalysisQualityReport(
    val mlKitSuccessRate: Double,
    val classificationCoverage: Double,
    val unknownRatio: Double,
    val lowConfidenceRatio: Double,
    val averageConfidence: Double,
    val eventReductionRatio: Double,
    val imagesPerSecond: Double,
    val excludedByReason: Map<ImageFilterStatus, Int>,
    val warnings: List<String>
)

data class TimeBucketCount(
    val dayOfWeek: String,
    val bucket: String,
    val count: Int,
    val ratio: Double
)

data class MonthlyTrendPoint(
    val month: String,
    val totalEvents: Int,
    val categoryCounts: Map<ActivityCategory, Int>,
    val topCategory: ActivityCategory?
)

data class InsightCard(
    val title: String,
    val message: String,
    val evidence: List<String>,
    val caution: String = "진단이 아니라 상담 질문 방향을 정하기 위한 보조 신호입니다."
)




enum class ConversationPhase(val koName: String) {
    SAFETY_CHECK("안전 확인"),
    RAPPORT("라포 형성"),
    PROBLEM_EXPLORATION("문제 상황 탐색"),
    PATTERN_REFLECTION("패턴 반영"),
    MICRO_ACTION_PLAN("작은 생활 개선 계획"),
    FOLLOW_UP("후속 확인")
}

enum class CounselingDomain(val koName: String) {
    SLEEP_RHYTHM("수면/생활리듬"),
    ACADEMIC_STRESS("학업/과제 부담"),
    SOCIAL_CONNECTION("사회적 연결"),
    BEHAVIORAL_ACTIVATION("활동/외출"),
    INTEREST_CONTINUITY("흥미/회복 자원"),
    MEAL_PATTERN("식사/일상 루틴"),
    HEALTH_STRESS("건강/몸 상태"),
    SAFETY_CHECK("안전 확인"),
    GENERAL_CHECK_IN("일반 안부 확인")
}

enum class CounselingPriority { LOW, MEDIUM, HIGH }

enum class CounselingEvidenceLevel {
    BEHAVIOR_PATTERN,
    VISUAL_AUXILIARY_CUE,
    USER_TEXT_DIRECT,
    QUESTIONNAIRE_DIRECT
}

data class BehaviorScores(
    val activityActivationScore: Double,
    val lifeRhythmInstabilityScore: Double,
    val socialWithdrawalSignalScore: Double,
    val academicLoadSignalScore: Double,
    val interestContinuityDropScore: Double,
    val mealPatternChangeScore: Double,
    val visualRiskCueScore: Double,
    val overallCounselingPriorityScore: Double
)

data class CounselingInsight(
    val domain: CounselingDomain,
    val domainKo: String,
    val priority: CounselingPriority,
    val confidence: Double,
    val evidenceLevel: CounselingEvidenceLevel,
    val safeInterpretation: String,
    val evidence: List<String>,
    val suggestedQuestions: List<String>,
    val privacySafeWording: String = "갤러리 내용을 직접 단정하지 않고, 생활 변화 가능성을 부드럽게 확인하는 질문입니다."
)

enum class VisualRiskCueType(val koName: String) {
    POSSIBLE_MEDICATION("약/복약 관련 가능 단서"),
    POSSIBLE_MEDICAL_CONTEXT("병원/의료 맥락 가능 단서"),
    POSSIBLE_INJURY_CONTEXT("상처/붕대/통증 가능 단서"),
    POSSIBLE_SHARP_OBJECT("날카로운 물체 가능 단서"),
    POSSIBLE_ISOLATED_NIGHT_CONTEXT("야간/고립 맥락 가능 단서"),
    POSSIBLE_THREAT_MESSAGE_SCREENSHOT("위협 메시지 캡처 가능 단서")
}

data class VisualRiskCue(
    val cueType: VisualRiskCueType,
    val cueNameKo: String,
    val imageCount: Int,
    val confidence: Double,
    val representativeEvidence: List<String>,
    val safetyInterpretation: String = "위험 확정이 아니라 상담자가 부드럽게 확인할 수 있는 보조 단서입니다."
)

enum class SafetyLevel {
    NONE,
    LOW_OBSERVE,
    MEDIUM_CHECK_IN,
    HIGH_SUPPORT,
    IMMEDIATE_DANGER
}

enum class SafetyAction {
    NORMAL_COUNSELING,
    SOFT_CHECK_IN,
    DIRECT_CHECK_IN,
    RULE_BASED_CRISIS_RESPONSE,
    EMERGENCY_GUIDE
}

data class SafetyAssessment(
    val level: SafetyLevel,
    val action: SafetyAction,
    val mustUseRuleBasedResponse: Boolean,
    val primaryTrigger: String,
    val reasons: List<String>,
    val responseGuideKo: String,
    val emergencyResourcesKo: List<String> = emptyList()
)



/** v3.4: WHO 청소년 건강 권고와 정렬된 상담 도메인. */
enum class WellbeingDomain(val koName: String) {
    SLEEP_AND_ROUTINE("수면/생활리듬"),
    PHYSICAL_ACTIVITY("신체활동/외출"),
    SEDENTARY_PATTERN("좌식/정적 패턴"),
    SOCIAL_CONNECTION("사회적 연결"),
    SCHOOL_STRESS("학업/학교 부담"),
    RECOVERY_RESOURCE("회복 자원/반복 활동"),
    MEAL_ROUTINE("식사/일상 루틴"),
    SAFETY_AND_VIOLENCE("안전/폭력 보조 확인"),
    GENERAL_CHECK_IN("일반 안부 확인")
}

enum class WellbeingLevel { NONE, LOW, MEDIUM, HIGH }

data class WellbeingDomainScore(
    val domain: WellbeingDomain,
    val domainKo: String,
    val level: WellbeingLevel,
    val score: Double,
    val confidence: Double,
    val recentEvents: Int,
    val baselineEvents: Int,
    val recentRatio: Double,
    val baselineRatio: Double,
    val deltaPercentPoint: Double,
    val evidence: List<String>,
    val counselingUse: String,
    val firstQuestion: String,
    val microActionCandidate: String,
    val interpretationPolicy: String
)

data class ProtectiveResource(
    val resourceNameKo: String,
    val sourceCategory: ActivityCategory,
    val recurrenceScore: Double,
    val activeWeekCount: Int,
    val activeMonthCount: Int,
    val observationSpanDays: Int,
    val status: String,
    val counselingUse: String,
    val evidence: List<String>
)

data class MicroActionCandidate(
    val domain: WellbeingDomain,
    val domainKo: String,
    val action: String,
    val whenToOffer: String,
    val caution: String
)

data class AuditExportResult(
    val saved: Boolean,
    val directoryPath: String?,
    val perImageCsvPath: String?,
    val perEventCsvPath: String?,
    val scenarioSummaryJsonPath: String?,
    val perImageRows: Int,
    val perEventRows: Int,
    val warning: String? = null
)

data class WellbeingAnalysisReport(
    val domainScores: List<WellbeingDomainScore>,
    val primaryDomains: List<WellbeingDomainScore>,
    val secondaryDomains: List<WellbeingDomainScore>,
    val protectiveResources: List<ProtectiveResource>,
    val microActionCandidates: List<MicroActionCandidate>,
    val conversationPhase: ConversationPhase,
    val suggestedOpening: String,
    val doNotStartWith: List<String>,
    val safetyPolicyNote: String,
    val llmPrompt: String,
    val auditExport: AuditExportResult? = null
)

data class CounselingAnalysisReport(
    val behaviorScores: BehaviorScores,
    val counselingInsights: List<CounselingInsight>,
    val visualRiskCues: List<VisualRiskCue>,
    val safetyAssessment: SafetyAssessment,
    val llmContextSummary: String,
    val caution: String = "이 결과는 진단이 아니라 상담 질문 방향을 정하기 위한 보조 정보입니다. 위기 상황은 사용자 직접 발화와 질문지 응답을 우선합니다.",
    val wellbeingReport: WellbeingAnalysisReport? = null
)

data class GalleryAnalysisResult(
    val analysisPeriod: AnalysisPeriod,
    val summary: AnalysisSummary,
    val qualityReport: AnalysisQualityReport,
    val sisyphusReview: SisyphusReviewReport,
    val preferences: List<PreferenceResult>,
    val periodChanges: List<PeriodChangeResult>,
    val nightChange: NightChangeResult,
    val timeDistribution: List<TimeBucketCount>,
    val monthlyTrends: List<MonthlyTrendPoint>,
    val insightCards: List<InsightCard>,
    val signals: List<SignalResult>,
    val counselingReport: CounselingAnalysisReport
) {
    companion object {
        fun empty(): GalleryAnalysisResult = GalleryAnalysisResult(
            analysisPeriod = AnalysisPeriod(),
            summary = AnalysisSummary(0, 0, 0, 0, 0, 0),
            qualityReport = AnalysisQualityReport(
                mlKitSuccessRate = 0.0,
                classificationCoverage = 0.0,
                unknownRatio = 1.0,
                lowConfidenceRatio = 1.0,
                averageConfidence = 0.0,
                eventReductionRatio = 0.0,
                imagesPerSecond = 0.0,
                excludedByReason = emptyMap(),
                warnings = listOf("갤러리 권한이 없어 일반 상담 모드로 진행합니다.")
            ),
            sisyphusReview = SisyphusReviewReport(emptyList(), 0),
            preferences = emptyList(),
            periodChanges = emptyList(),
            nightChange = NightChangeResult(0, 0, 0.0, 0.0, 0.0),
            timeDistribution = emptyList(),
            monthlyTrends = emptyList(),
            insightCards = emptyList(),
            signals = listOf(
                SignalResult(
                    signalType = SignalType.ANALYSIS_CONFIDENCE_LOW,
                    strength = SignalStrength.LOW,
                    confidence = 0.0,
                    recommendedQuestionDomain = listOf("general_check_in"),
                    explanation = "갤러리 접근 권한이 없어 이미지 기반 생활 패턴 보조 신호를 만들지 않았습니다."
                )
            ),
            counselingReport = CounselingAnalysisReport(
                behaviorScores = BehaviorScores(
                    activityActivationScore = 0.0,
                    lifeRhythmInstabilityScore = 0.0,
                    socialWithdrawalSignalScore = 0.0,
                    academicLoadSignalScore = 0.0,
                    interestContinuityDropScore = 0.0,
                    mealPatternChangeScore = 0.0,
                    visualRiskCueScore = 0.0,
                    overallCounselingPriorityScore = 0.0
                ),
                counselingInsights = emptyList(),
                visualRiskCues = emptyList(),
                safetyAssessment = SafetyAssessment(
                    level = SafetyLevel.NONE,
                    action = SafetyAction.NORMAL_COUNSELING,
                    mustUseRuleBasedResponse = false,
                    primaryTrigger = "NO_GALLERY_PERMISSION",
                    reasons = listOf("갤러리 접근 권한이 없어 이미지 기반 신호를 만들지 않았습니다."),
                    responseGuideKo = "일반 안부 확인 질문으로 시작하세요."
                ),
                llmContextSummary = "갤러리 권한 없음. 일반 상담 모드."
            )
        )
    }
}

data class AnalysisConfig(
    val longTermMonths: Int = 6,
    val recentDays: Long = 14,
    val baselineDays: Long = 30,
    val thumbnailLongSide: Int = 384,
    val maxImages: Int = Int.MAX_VALUE,
    val reviewIterations: Int = 3,
    val progressEvery: Int = 8,
    val classifierMinConfidence: Double = 0.38,
    val enableAuditExport: Boolean = true
)
