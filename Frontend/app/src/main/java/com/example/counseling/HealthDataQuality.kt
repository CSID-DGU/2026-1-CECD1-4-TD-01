package com.example.counseling

import java.time.LocalDate

enum class HealthMetricKind(val label: String) {
    Steps("걸음"),
    Calories("총 소모 칼로리"),
    ActiveCalories("활동 칼로리"),
    Distance("이동 거리"),
    HeartRate("평균 심박수"),
    Sleep("수면"),
}

data class HealthDataExclusion(
    val metric: HealthMetricKind,
    val reason: String,
)

internal data class RawHealthDayMetrics(
    val steps: Long?,
    val caloriesKcal: Double?,
    val activeCaloriesKcal: Double?,
    val distanceKm: Double?,
    val heartRateBpm: Long?,
    val sleepMinutes: Long?,
)

private const val MaxDailySteps = 200_000L
private const val MinDistanceKm = 0.02
private const val MaxDistanceKm = 200.0
private const val MinMetersPerStep = 0.20
private const val MaxMetersPerStep = 2.0
private const val MinTotalCaloriesKcal = 50.0
private const val MaxTotalCaloriesKcal = 15_000.0
private const val MinActiveCaloriesKcal = 5.0
private const val MaxActiveCaloriesKcal = 10_000.0
private const val MinHeartRateBpm = 35L
private const val MaxHeartRateBpm = 220L
private const val MinSleepMinutes = 6L
private const val MaxSleepMinutes = 24L * 60L

internal fun sanitizeHealthDay(
    date: LocalDate,
    raw: RawHealthDayMetrics,
): HealthDaySummary {
    val valid = mutableSetOf<HealthMetricKind>()
    val exclusions = mutableListOf<HealthDataExclusion>()

    fun exclude(metric: HealthMetricKind, reason: String) {
        valid.remove(metric)
        exclusions.removeAll { it.metric == metric }
        exclusions += HealthDataExclusion(metric, reason)
    }

    var steps = raw.steps ?: 0L
    when {
        raw.steps == null -> exclude(HealthMetricKind.Steps, "기록을 받지 못함")
        steps <= 0L -> exclude(HealthMetricKind.Steps, "0이라서 미수신 가능성이 있음")
        steps > MaxDailySteps -> {
            steps = 0L
            exclude(HealthMetricKind.Steps, "일일 범위를 벗어난 값")
        }
        else -> valid += HealthMetricKind.Steps
    }

    var distanceKm = raw.distanceKm ?: 0.0
    when {
        raw.distanceKm == null -> exclude(HealthMetricKind.Distance, "기록을 받지 못함")
        !distanceKm.isFinite() -> {
            distanceKm = 0.0
            exclude(HealthMetricKind.Distance, "유효하지 않은 숫자")
        }
        distanceKm <= MinDistanceKm -> {
            distanceKm = 0.0
            exclude(HealthMetricKind.Distance, "0 또는 너무 작은 값")
        }
        distanceKm > MaxDistanceKm -> {
            distanceKm = 0.0
            exclude(HealthMetricKind.Distance, "일일 범위를 벗어난 값")
        }
        else -> valid += HealthMetricKind.Distance
    }

    if (
        HealthMetricKind.Steps in valid &&
        HealthMetricKind.Distance in valid &&
        steps >= 100L
    ) {
        val metersPerStep = distanceKm * 1_000.0 / steps
        if (metersPerStep !in MinMetersPerStep..MaxMetersPerStep) {
            distanceKm = 0.0
            exclude(HealthMetricKind.Distance, "걸음 수와 이동 거리의 비율이 모순됨")
        }
    }

    var caloriesKcal = raw.caloriesKcal ?: 0.0
    when {
        raw.caloriesKcal == null -> exclude(HealthMetricKind.Calories, "기록을 받지 못함")
        !caloriesKcal.isFinite() -> {
            caloriesKcal = 0.0
            exclude(HealthMetricKind.Calories, "유효하지 않은 숫자")
        }
        caloriesKcal < MinTotalCaloriesKcal -> {
            caloriesKcal = 0.0
            exclude(HealthMetricKind.Calories, "0 또는 너무 작은 값")
        }
        caloriesKcal > MaxTotalCaloriesKcal -> {
            caloriesKcal = 0.0
            exclude(HealthMetricKind.Calories, "일일 범위를 벗어난 값")
        }
        else -> valid += HealthMetricKind.Calories
    }

    var activeCaloriesKcal = raw.activeCaloriesKcal ?: 0.0
    when {
        raw.activeCaloriesKcal == null -> exclude(HealthMetricKind.ActiveCalories, "기록을 받지 못함")
        !activeCaloriesKcal.isFinite() -> {
            activeCaloriesKcal = 0.0
            exclude(HealthMetricKind.ActiveCalories, "유효하지 않은 숫자")
        }
        activeCaloriesKcal < MinActiveCaloriesKcal -> {
            activeCaloriesKcal = 0.0
            exclude(HealthMetricKind.ActiveCalories, "0 또는 너무 작은 값")
        }
        activeCaloriesKcal > MaxActiveCaloriesKcal -> {
            activeCaloriesKcal = 0.0
            exclude(HealthMetricKind.ActiveCalories, "일일 범위를 벗어난 값")
        }
        else -> valid += HealthMetricKind.ActiveCalories
    }

    if (
        HealthMetricKind.Calories in valid &&
        HealthMetricKind.ActiveCalories in valid &&
        activeCaloriesKcal > caloriesKcal
    ) {
        caloriesKcal = 0.0
        exclude(HealthMetricKind.Calories, "활동 칼로리보다 작아 모순됨")
    }

    var heartRateBpm = raw.heartRateBpm
    when {
        raw.heartRateBpm == null -> exclude(HealthMetricKind.HeartRate, "기록을 받지 못함")
        raw.heartRateBpm !in MinHeartRateBpm..MaxHeartRateBpm -> {
            heartRateBpm = null
            exclude(HealthMetricKind.HeartRate, "35~220 bpm 범위를 벗어난 값")
        }
        else -> valid += HealthMetricKind.HeartRate
    }

    var sleepHours = (raw.sleepMinutes ?: 0L) / 60.0
    when {
        raw.sleepMinutes == null -> exclude(HealthMetricKind.Sleep, "기록을 받지 못함")
        raw.sleepMinutes <= MinSleepMinutes -> {
            sleepHours = 0.0
            exclude(HealthMetricKind.Sleep, "0 또는 6분 이하의 너무 작은 값")
        }
        raw.sleepMinutes > MaxSleepMinutes -> {
            sleepHours = 0.0
            exclude(HealthMetricKind.Sleep, "24시간을 넘는 값")
        }
        else -> valid += HealthMetricKind.Sleep
    }

    return HealthDaySummary(
        date = date,
        steps = steps,
        caloriesKcal = caloriesKcal,
        activeCaloriesKcal = activeCaloriesKcal,
        distanceKm = distanceKm,
        heartRateBpm = heartRateBpm,
        sleepHours = sleepHours,
        validMetrics = valid,
        exclusions = exclusions,
    )
}

internal fun summarizeHealthDays(
    period: HealthPeriod,
    daily: List<HealthDaySummary>,
): HealthSummary {
    val validMetrics = HealthMetricKind.entries
        .filterTo(mutableSetOf()) { metric -> daily.any { it.hasValidMetric(metric) } }
    val heartRates = daily
        .filter { it.hasValidMetric(HealthMetricKind.HeartRate) }
        .mapNotNull { it.heartRateBpm }
    val excludedCount = daily.sumOf { it.exclusions.size }
    val baseMessage = "이번 ${period.label} Health Connect 요약과 날짜별 유효 기록을 표시하고 있습니다."
    val qualityMessage = if (excludedCount > 0) {
        " 0·누락·모순·극소값 ${excludedCount}건은 계산에서 제외했습니다."
    } else {
        ""
    }
    return HealthSummary(
        period = period,
        steps = daily.filter { it.hasValidMetric(HealthMetricKind.Steps) }.sumOf { it.steps },
        distanceKm = daily.filter { it.hasValidMetric(HealthMetricKind.Distance) }.sumOf { it.distanceKm },
        caloriesKcal = daily.filter { it.hasValidMetric(HealthMetricKind.Calories) }.sumOf { it.caloriesKcal },
        activeCaloriesKcal = daily
            .filter { it.hasValidMetric(HealthMetricKind.ActiveCalories) }
            .sumOf { it.activeCaloriesKcal },
        heartRateBpm = heartRates.takeIf { it.isNotEmpty() }?.average()?.toLong(),
        sleepHours = daily.filter { it.hasValidMetric(HealthMetricKind.Sleep) }.sumOf { it.sleepHours },
        daily = daily,
        validMetrics = validMetrics,
        excludedMetricCount = excludedCount,
        message = baseMessage + qualityMessage,
    )
}

fun HealthSummary.hasValidMetric(metric: HealthMetricKind): Boolean = metric in validMetrics

fun HealthDaySummary.hasValidMetric(metric: HealthMetricKind): Boolean = metric in validMetrics

fun HealthDaySummary.hasAnyValidMetric(): Boolean = validMetrics.isNotEmpty()
