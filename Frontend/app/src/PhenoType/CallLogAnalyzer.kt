package com.psychocare.phenotype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.psychocare.data.CallLogEntry
import com.psychocare.data.CallLogSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 통화 기록 분석기
 *
 * 필요 권한: READ_CALL_LOG (런타임 요청 필요)
 *
 * 수집 데이터:
 *  - 최근 14일 통화 기록 (이번 주 vs 지난 주 비교)
 *  - 고유 연락처 수, 평균 통화 시간, 부재중 비율
 *  - 연속 무연락 일수 → 고립 경보 트리거
 */
class CallLogAnalyzer(private val context: Context) {

    private val TAG = "CallLogAnalyzer"

    // ─────────────────────────────────────────────────────────────────
    // 권한 확인
    // ─────────────────────────────────────────────────────────────────

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────────────────────────────
    // 분석 진입점
    // ─────────────────────────────────────────────────────────────────

    suspend fun analyze(): CallLogSummary? = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(TAG, "READ_CALL_LOG 권한 없음")
            return@withContext null
        }

        val now = System.currentTimeMillis()
        val oneWeekMs = 7L * 24 * 60 * 60 * 1000
        val thisWeekStart = now - oneWeekMs
        val prevWeekStart = now - 2 * oneWeekMs

        // 최근 14일 전체 쿼리 후 주별 분리
        val allEntries = queryCallLog(prevWeekStart, now)
        val thisWeek = allEntries.filter { it.dateMs >= thisWeekStart }
        val prevWeek = allEntries.filter { it.dateMs < thisWeekStart }

        val thisCount = thisWeek.size
        val prevCount = prevWeek.size

        // 주간 변화율 (%)
        val weeklyChangePct = if (prevCount > 0) {
            ((thisCount - prevCount).toFloat() / prevCount) * 100f
        } else 0f

        // 고유 연락처 수 (번호 기준)
        val uniqueContacts = thisWeek.map { it.number.trimStart('+') }.toSet().size

        // 부재중 통계
        val missedCount = thisWeek.count { it.callType == CallLog.Calls.MISSED_TYPE }
        val missedRate = if (thisCount > 0) missedCount.toFloat() / thisCount else 0f

        // 평균 통화 시간 (수·발신만, 부재중 제외)
        val connectedCalls = thisWeek.filter {
            it.callType != CallLog.Calls.MISSED_TYPE && it.durationSec > 0
        }
        val avgDuration = if (connectedCalls.isNotEmpty()) {
            connectedCalls.map { it.durationSec }.average().toFloat()
        } else 0f

        // 연속 무연락일 계산 (오늘 기준 역방향)
        val zeroDayStreak = calcZeroCommunicationStreak(thisWeek)

        // 고립 경보: 직전 주 대비 이번 주 통화 50% 이상 감소
        val isolationAlert = prevCount >= 3 && weeklyChangePct <= -50f

        // 저장된 연락처 비율 (CACHED_NAME 있는 경우)
        val namedRate = if (thisCount > 0) {
            thisWeek.count { !it.cachedName.isNullOrBlank() }.toFloat() / thisCount
        } else 0f

        Log.d(TAG, "통화 분석 완료 — 이번주:$thisCount 지난주:$prevCount 무연락:${zeroDayStreak}일")

        CallLogSummary(
            totalCallsThisWeek     = thisCount,
            totalCallsPrevWeek     = prevCount,
            uniqueContactsThisWeek = uniqueContacts,
            avgDurationSec         = avgDuration,
            missedCallCount        = missedCount,
            missedCallRate         = missedRate,
            zeroCommunicationStreak = zeroDayStreak,
            weeklyChangePct        = weeklyChangePct,
            isolationAlert         = isolationAlert,
            namedContactsRate      = namedRate
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // ContentResolver 쿼리
    // ─────────────────────────────────────────────────────────────────

    private fun queryCallLog(fromMs: Long, toMs: Long): List<CallLogEntry> {
        val results = mutableListOf<CallLogEntry>()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf(fromMs.toString(), toMs.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx   = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx   = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx   = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx    = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                while (cursor.moveToNext()) {
                    results.add(
                        CallLogEntry(
                            number      = cursor.getString(numberIdx) ?: "",
                            cachedName  = cursor.getString(nameIdx),
                            callType    = cursor.getInt(typeIdx),
                            dateMs      = cursor.getLong(dateIdx),
                            durationSec = cursor.getLong(durIdx)
                        )
                    )
                }
            }
        }.onFailure {
            Log.e(TAG, "통화 기록 쿼리 실패: ${it.message}")
        }

        return results
    }

    // ─────────────────────────────────────────────────────────────────
    // 연속 무연락일 계산
    // ─────────────────────────────────────────────────────────────────

    /**
     * 오늘부터 역방향으로 통화 기록이 없는 연속 일수를 반환
     */
    private fun calcZeroCommunicationStreak(entries: List<CallLogEntry>): Int {
        // 통화가 있었던 날의 "날짜 문자열" 집합 (yyyy-MM-dd)
        val daysWithCall = entries.map { entry ->
            Calendar.getInstance().run {
                timeInMillis = entry.dateMs
                "%04d-%02d-%02d".format(
                    get(Calendar.YEAR),
                    get(Calendar.MONTH) + 1,
                    get(Calendar.DAY_OF_MONTH)
                )
            }
        }.toSet()

        var streak = 0
        val cal = Calendar.getInstance()

        repeat(7) {
            val key = "%04d-%02d-%02d".format(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            if (key !in daysWithCall) streak++
            else return streak   // 통화 있는 날 나오면 종료
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        return streak
    }
}
