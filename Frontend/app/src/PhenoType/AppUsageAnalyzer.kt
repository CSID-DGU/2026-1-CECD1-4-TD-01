package com.psychocare.phenotype

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import com.psychocare.data.AppCategory
import com.psychocare.data.AppUsageEntry
import com.psychocare.data.AppUsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 앱 사용통계 분석기
 *
 * 필요 권한: PACKAGE_USAGE_STATS
 *   → 일반 runtime 권한이 아님. 사용자가 직접 설정 > 앱 > 특별한 앱 접근 > 사용 정보 접근에서 허용 필요.
 *   → Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) 로 설정 화면 이동 유도.
 *
 * 수집 데이터:
 *  - 7일간 앱별 사용 시간 (카테고리 분류 포함)
 *  - 일일 평균 스크린 타임
 *  - 야간(00~06시) 사용 시간
 *  - 최장 단일 연속 세션
 *  - 주간 스크린타임 변화율
 */
class AppUsageAnalyzer(private val context: Context) {

    private val TAG = "AppUsageAnalyzer"

    // ─────────────────────────────────────────────────────────────────
    // 권한 확인 (AppOpsManager 방식 — 일반 checkSelfPermission 으로 안 됨)
    // ─────────────────────────────────────────────────────────────────

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ─────────────────────────────────────────────────────────────────
    // 분석 진입점
    // ─────────────────────────────────────────────────────────────────

    suspend fun analyze(): AppUsageSummary = withContext(Dispatchers.IO) {

        if (!hasPermission()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS 권한 없음 — 설정에서 허용 필요")
            return@withContext AppUsageSummary(
                topApps = emptyList(),
                dailyAvgScreenTimeMin = 0,
                dailyAvgLateNightMin  = 0,
                longestSingleSessionMin = 0,
                weeklyChangePct = 0f,
                hasPermission = false
            )
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm  = context.packageManager

        val now           = System.currentTimeMillis()
        val oneWeekMs     = 7L * 24 * 60 * 60 * 1000
        val thisWeekStart = now - oneWeekMs
        val prevWeekStart = now - 2 * oneWeekMs

        // ── 이번 주 / 지난 주 일별 통계 ──────────────────────────────
        val thisWeekStats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, thisWeekStart, now
        ) ?: emptyList()

        val prevWeekStats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, prevWeekStart, thisWeekStart
        ) ?: emptyList()

        // ── 앱별 총 사용 시간 집계 (이번 주) ─────────────────────────
        val eventUsageMs = calcForegroundUsageByPackage(usm, thisWeekStart, now)
        val statUsageMs = mutableMapOf<String, Long>()
        thisWeekStats.forEach { stat ->
            if (stat.totalTimeInForeground > 0) {
                statUsageMs[stat.packageName] =
                    (statUsageMs[stat.packageName] ?: 0L) + stat.totalTimeInForeground
            }
        }
        val appTotalMs = (eventUsageMs.keys + statUsageMs.keys).associateWith { pkg ->
            maxOf(eventUsageMs[pkg] ?: 0L, statUsageMs[pkg] ?: 0L)
        }

        val thisWeekTotalMs = appTotalMs.values.sum()
        val prevEventUsageMs = calcForegroundUsageByPackage(usm, prevWeekStart, thisWeekStart)
        val prevStatTotalMs = prevWeekStats.sumOf { it.totalTimeInForeground }
        val prevWeekTotalMs = maxOf(prevEventUsageMs.values.sum(), prevStatTotalMs)

        val weeklyChangePct = if (prevWeekTotalMs > 0) {
            ((thisWeekTotalMs - prevWeekTotalMs).toFloat() / prevWeekTotalMs) * 100f
        } else 0f

        // ── 앱별 사용 시간 목록 (7일 합산 1분 이상) ────
        val topApps = appTotalMs.entries
            .filter { it.value >= 60_000L }
            .sortedByDescending { it.value }
            .take(30)
            .mapNotNull { (pkg, ms) ->
                runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    AppUsageEntry(
                        packageName  = pkg,
                        appName      = pm.getApplicationLabel(appInfo).toString(),
                        totalTimeMin = ms / 60_000,
                        category     = categorizeApp(appInfo, pkg)
                    )
                }.getOrNull()
            }

        // ── 야간 사용 시간 (이벤트 기반) ─────────────────────────────
        val lateNightTotalMin = calcLateNightUsage(usm, thisWeekStart, now)

        // ── 최장 단일 세션 ────────────────────────────────────────────
        val longestMin = calcLongestSession(usm, thisWeekStart, now)

        Log.d(TAG, "앱 사용 분석 완료 — 일평균:${thisWeekTotalMs / 7 / 60_000}분 " +
                "야간:${lateNightTotalMin / 7}분/일 최장세션:${longestMin}분")

        AppUsageSummary(
            topApps                = topApps,
            dailyAvgScreenTimeMin  = (thisWeekTotalMs / 7) / 60_000,
            dailyAvgLateNightMin   = lateNightTotalMin / 7,
            longestSingleSessionMin = longestMin,
            weeklyChangePct        = weeklyChangePct,
            hasPermission          = true
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // 앱 카테고리 분류
    // ─────────────────────────────────────────────────────────────────

    private fun categorizeApp(appInfo: ApplicationInfo, pkg: String): AppCategory {
        // Android O 이상: ApplicationInfo.category 활용
        val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appInfo.category
        } else -1

        return when {
            category == ApplicationInfo.CATEGORY_GAME         -> AppCategory.GAME
            category == ApplicationInfo.CATEGORY_VIDEO        -> AppCategory.VIDEO
            category == ApplicationInfo.CATEGORY_SOCIAL       -> AppCategory.SNS
            category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
            // 패키지명 키워드 fallback
            SNS_PACKAGES.any { pkg.contains(it, ignoreCase = true) }   -> AppCategory.SNS
            VIDEO_PACKAGES.any { pkg.contains(it, ignoreCase = true) }  -> AppCategory.VIDEO
            GAME_PACKAGES.any { pkg.contains(it, ignoreCase = true) }   -> AppCategory.GAME
            else                                                          -> AppCategory.OTHER
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 야간(00~06시) 총 사용 시간 계산 (이벤트 기반, 분 단위 반환)
    // ─────────────────────────────────────────────────────────────────

    private fun calcLateNightUsage(usm: UsageStatsManager, from: Long, to: Long): Long {
        val events = usm.queryEvents(from, to) ?: return 0L
        var totalMs = 0L
        var sessionStart = -1L
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val hour = Calendar.getInstance().run {
                timeInMillis = event.timeStamp
                get(Calendar.HOUR_OF_DAY)
            }
            val isLateNight = hour in 0..5

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (isLateNight) sessionStart = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED  -> {
                    if (isLateNight && sessionStart > 0) {
                        totalMs += event.timeStamp - sessionStart
                        sessionStart = -1L
                    }
                }
            }
        }

        return totalMs / 60_000
    }

    private fun calcForegroundUsageByPackage(usm: UsageStatsManager, from: Long, to: Long): Map<String, Long> {
        val events = usm.queryEvents(from, to) ?: return emptyMap()
        val totals = mutableMapOf<String, Long>()
        val activeStarts = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    activeStarts[packageName] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val startedAt = activeStarts.remove(packageName)
                    if (startedAt != null && event.timeStamp > startedAt) {
                        totals[packageName] = (totals[packageName] ?: 0L) + (event.timeStamp - startedAt)
                    }
                }
            }
        }

        activeStarts.forEach { (packageName, startedAt) ->
            if (to > startedAt) {
                totals[packageName] = (totals[packageName] ?: 0L) + (to - startedAt)
            }
        }

        return totals
    }

    // ─────────────────────────────────────────────────────────────────
    // 최장 단일 연속 세션 계산 (이벤트 기반, 분 단위 반환)
    // ─────────────────────────────────────────────────────────────────

    private fun calcLongestSession(usm: UsageStatsManager, from: Long, to: Long): Long {
        val events = usm.queryEvents(from, to) ?: return 0L
        var longestMs = 0L
        var sessionStart = -1L
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (sessionStart < 0) sessionStart = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED  -> {
                    if (sessionStart > 0) {
                        val duration = event.timeStamp - sessionStart
                        if (duration > longestMs) longestMs = duration
                        sessionStart = -1L
                    }
                }
            }
        }

        return longestMs / 60_000
    }

    // ─────────────────────────────────────────────────────────────────
    // 카테고리 분류용 패키지명 키워드
    // ─────────────────────────────────────────────────────────────────

    companion object {
        private val SNS_PACKAGES = listOf(
            "instagram", "twitter", "facebook", "tiktok",
            "kakao.talk", "naver.band", "snapchat", "discord"
        )
        private val VIDEO_PACKAGES = listOf(
            "youtube", "netflix", "twitch", "wavve",
            "watcha", "tving", "vlive", "coupangplay"
        )
        private val GAME_PACKAGES = listOf(
            "game", "supercell", "mihoyo", "garena",
            "nexon", "netmarble", "ncsoft", "kakao.games"
        )
    }
}
