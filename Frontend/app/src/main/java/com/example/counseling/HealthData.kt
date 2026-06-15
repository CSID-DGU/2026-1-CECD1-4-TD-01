package com.example.counseling

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

suspend fun readHealthSummary(context: Context, period: HealthPeriod): HealthSummary = withContext(Dispatchers.IO) {
    when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_UNAVAILABLE -> {
            return@withContext HealthSummary(period = period, message = "이 기기에서는 Health Connect를 사용할 수 없습니다.")
        }
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
            return@withContext HealthSummary(period = period, message = "Health Connect 설치 또는 업데이트가 필요합니다.")
        }
    }

    val client = HealthConnectClient.getOrCreate(context)
    val granted = client.permissionController.getGrantedPermissions()
    if (!granted.containsAll(healthPermissions)) {
        return@withContext HealthSummary(period = period, message = "권한 연결을 눌러 Health Connect 읽기 권한을 허용해 주세요.")
    }

    runCatching {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = when (period) {
            HealthPeriod.Week -> today.minusDays((today.dayOfWeek.value - 1).toLong())
            HealthPeriod.Month -> today.withDayOfMonth(1)
        }
        val daily = buildList {
            var date = today
            while (!date.isBefore(startDate)) {
                add(readHealthDaySummary(client, date, zone))
                date = date.minusDays(1)
            }
        }.sortedByDescending { it.date }
        val heartRates = daily.mapNotNull { it.heartRateBpm }
        HealthSummary(
            period = period,
            steps = daily.sumOf { it.steps },
            distanceKm = daily.sumOf { it.distanceKm },
            caloriesKcal = daily.sumOf { it.caloriesKcal },
            activeCaloriesKcal = daily.sumOf { it.activeCaloriesKcal },
            heartRateBpm = heartRates.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            sleepHours = daily.sumOf { it.sleepHours },
            daily = daily,
            message = "이번 ${period.label} Health Connect 요약과 날짜별 기록을 표시하고 있습니다.",
        )
    }.getOrElse {
        HealthSummary(period = period, message = "건강 데이터 읽기 실패: ${it.message ?: it.javaClass.simpleName}")
    }
}

suspend fun refreshHealthRagSlot(context: Context, period: HealthPeriod): String? {
    val summary = readHealthSummary(context, period)
    val contextText = summary.toPromptContext()
    if (contextText != null) {
        replaceRagSlot(
            context = context,
            slot = RagSlot.Health,
            contextText = contextText,
            source = "Health Connect ${period.label} 요약",
        )
    }
    return contextText
}

suspend fun readHealthDaySummary(
    client: HealthConnectClient,
    date: LocalDate,
    zone: ZoneId,
): HealthDaySummary {
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().coerceAtMost(Instant.now())
    val result = client.aggregate(
        AggregateRequest(
            metrics = setOf(
                StepsRecord.COUNT_TOTAL,
                DistanceRecord.DISTANCE_TOTAL,
                TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                HeartRateRecord.BPM_AVG,
                SleepSessionRecord.SLEEP_DURATION_TOTAL,
            ),
            timeRangeFilter = TimeRangeFilter.between(start, end),
        ),
    )
    return HealthDaySummary(
        date = date,
        steps = result[StepsRecord.COUNT_TOTAL] ?: 0L,
        distanceKm = result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0,
        caloriesKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
        activeCaloriesKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
        heartRateBpm = result[HeartRateRecord.BPM_AVG]?.toLong(),
        sleepHours = (result[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes() ?: 0L) / 60.0,
    )
}

fun Instant.coerceAtMost(maximum: Instant): Instant {
    return if (isAfter(maximum)) maximum else this
}

