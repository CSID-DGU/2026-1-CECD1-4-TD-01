package com.psychocare.phenotype

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.psychocare.data.CalendarEntry
import com.psychocare.data.CalendarSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 캘린더 분석기
 *
 * 필요 권한: READ_CALENDAR (런타임 요청 필요)
 *
 * 수집 데이터:
 *  - 과거 14일 및 미래 14일 간의 캘린더 일정
 */
class CalendarAnalyzer(private val context: Context) {

    private val TAG = "CalendarAnalyzer"

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun analyze(): CalendarSummary? = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.w(TAG, "READ_CALENDAR 권한 없음")
            return@withContext CalendarSummary(emptyList(), emptyList(), 0, 0, false)
        }

        val now = System.currentTimeMillis()
        val fourteenDaysMs = 14L * 24 * 60 * 60 * 1000
        val startTime = 0L // 과거 전체 일정 조회 (원본 전송용)
        val endTime = now + fourteenDaysMs // 미래는 14일까지만 조회

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startTime.toString(), endTime.toString())

        val pastEvents = mutableListOf<CalendarEntry>()
        val upcomingEvents = mutableListOf<CalendarEntry>()

        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val dtStartIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val dtEndIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)

                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIndex) ?: "제목 없음"
                    val dtStart = cursor.getLong(dtStartIndex)
                    val dtEnd = cursor.getLong(dtEndIndex)
                    val isAllDay = cursor.getInt(allDayIndex) == 1

                    val entry = CalendarEntry(
                        title = title,
                        dateMs = dtStart,
                        endDateMs = dtEnd,
                        isAllDay = isAllDay
                    )

                    if (dtStart < now) {
                        pastEvents.add(entry)
                    } else {
                        upcomingEvents.add(entry)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "캘린더 쿼리 중 오류 발생", e)
        }

        Log.d(TAG, "캘린더 쿼리 완료 - 과거: ${pastEvents.size}, 예정: ${upcomingEvents.size}")

        CalendarSummary(
            upcomingEvents = upcomingEvents,
            pastEvents = pastEvents,
            upcomingCount = upcomingEvents.size,
            pastCount = pastEvents.size,
            hasPermission = true
        )
    }
}
