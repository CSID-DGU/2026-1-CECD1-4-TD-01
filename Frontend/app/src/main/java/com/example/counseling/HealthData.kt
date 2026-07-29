package com.example.counseling

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
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
    if (granted.intersect(healthPermissions).isEmpty()) {
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
                add(readHealthDaySummary(client, date, zone, granted))
                date = date.minusDays(1)
            }
        }.sortedByDescending { it.date }
        val summary = summarizeHealthDays(period, daily)
        val missingCount = healthPermissions.count { it !in granted }
        if (missingCount == 0) summary else summary.copy(
            message = summary.message +
                " 기본 ${healthPermissions.size}개 항목 중 ${missingCount}개는 권한이 없어 나머지 기록만 계산했습니다.",
        )
    }.getOrElse {
        HealthSummary(period = period, message = "건강 데이터 읽기 실패: ${it.message ?: it.javaClass.simpleName}")
    }
}

suspend fun refreshHealthRagSlot(context: Context, period: HealthPeriod): String? {
    val summary = readHealthSummary(context, period)
    val overview = readExtendedHealthOverview(context, period)
    val contextText = listOfNotNull(summary.toPromptContext(), overview.toPromptContext())
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }
    if (contextText != null) {
        replaceRagSlot(
            context = context,
            slot = RagSlot.Health,
            contextText = contextText,
            source = "Health Connect ${period.label} 요약",
        )
    }
    if (contextText == null) clearRagSlot(context, RagSlot.Health)
    return contextText
}

suspend fun readHealthDaySummary(
    client: HealthConnectClient,
    date: LocalDate,
    zone: ZoneId,
    grantedPermissions: Set<String>,
): HealthDaySummary {
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().coerceAtMost(Instant.now())
    val metrics = buildSet {
        if (HealthPermission.getReadPermission(StepsRecord::class) in grantedPermissions) add(StepsRecord.COUNT_TOTAL)
        if (HealthPermission.getReadPermission(DistanceRecord::class) in grantedPermissions) add(DistanceRecord.DISTANCE_TOTAL)
        if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in grantedPermissions) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in grantedPermissions) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in grantedPermissions) add(HeartRateRecord.BPM_AVG)
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in grantedPermissions) add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
    }
    val result = client.aggregate(
        AggregateRequest(
            metrics = metrics,
            timeRangeFilter = TimeRangeFilter.between(start, end),
        ),
    )
    fun permission(recordPermission: String): Boolean = recordPermission in grantedPermissions
    return sanitizeHealthDay(
        date = date,
        raw = RawHealthDayMetrics(
            steps = result[StepsRecord.COUNT_TOTAL].takeIf { permission(HealthPermission.getReadPermission(StepsRecord::class)) },
            distanceKm = result[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
                ?.takeIf { permission(HealthPermission.getReadPermission(DistanceRecord::class)) },
            caloriesKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                ?.takeIf { permission(HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)) },
            activeCaloriesKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                ?.takeIf { permission(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)) },
            heartRateBpm = result[HeartRateRecord.BPM_AVG]
                ?.takeIf { permission(HealthPermission.getReadPermission(HeartRateRecord::class)) },
            sleepMinutes = result[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
                ?.takeIf { permission(HealthPermission.getReadPermission(SleepSessionRecord::class)) },
        ),
    )
}

fun Instant.coerceAtMost(maximum: Instant): Instant {
    return if (isAfter(maximum)) maximum else this
}

