package com.psychocare.data

// ─────────────────────────────────────────────
// 통화 기록 원시 데이터
// ─────────────────────────────────────────────
data class CallLogEntry(
    val number: String,
    val cachedName: String?,        // 저장된 연락처 이름 (없으면 null)
    val callType: Int,              // INCOMING / OUTGOING / MISSED
    val dateMs: Long,
    val durationSec: Long
)

// ─────────────────────────────────────────────
// 통화 기록 분석 결과
// ─────────────────────────────────────────────
data class CallLogSummary(
    val totalCallsThisWeek: Int,            // 이번 주 통화 수
    val totalCallsPrevWeek: Int,            // 지난 주 통화 수
    val uniqueContactsThisWeek: Int,        // 이번 주 고유 연락처 수
    val avgDurationSec: Float,              // 평균 통화 시간(초)
    val missedCallCount: Int,               // 부재중 횟수
    val missedCallRate: Float,              // 부재중 비율 (0.0 ~ 1.0)
    val zeroCommunicationStreak: Int,       // 연속 연락 없는 날 수
    val weeklyChangePct: Float,             // 주간 통화 변화율 (%)
    val isolationAlert: Boolean,            // 대인 교류 급감 경보
    val namedContactsRate: Float            // 저장된 연락처로의 통화 비율
)

// ─────────────────────────────────────────────
// 앱 카테고리
// ─────────────────────────────────────────────
enum class AppCategory(val korean: String) {
    GAME("게임"),
    VIDEO("동영상"),
    SNS("소셜"),
    PRODUCTIVITY("생산성"),
    OTHER("기타")
}

// ─────────────────────────────────────────────
// 앱별 사용 시간
// ─────────────────────────────────────────────
data class AppUsageEntry(
    val packageName: String,
    val appName: String,
    val totalTimeMin: Long,
    val category: AppCategory
)

// ─────────────────────────────────────────────
// 앱 사용 통계 분석 결과
// ─────────────────────────────────────────────
data class AppUsageSummary(
    val topApps: List<AppUsageEntry>,       // 사용 시간 상위 앱
    val dailyAvgScreenTimeMin: Long,        // 7일 평균 일일 스크린 타임 (분)
    val dailyAvgLateNightMin: Long,         // 야간(00~06시) 7일 평균 사용 (분)
    val longestSingleSessionMin: Long,      // 최장 단일 연속 세션 (분)
    val weeklyChangePct: Float,             // 주간 스크린타임 변화율 (%)
    val hasPermission: Boolean              // PACKAGE_USAGE_STATS 권한 여부
)

// ─────────────────────────────────────────────
// 통합 피노타입 데이터
// ─────────────────────────────────────────────
data class PhenotypeData(
    val callLog: CallLogSummary?,
    val appUsage: AppUsageSummary?,
    val collectedAt: Long = System.currentTimeMillis()
)
