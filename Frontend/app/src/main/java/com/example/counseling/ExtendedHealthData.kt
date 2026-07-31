package com.example.counseling

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.reflect.KClass

enum class HealthDataAvailability(val label: String) {
    Received("수신됨"),
    NoData("기록 없음"),
    PermissionRequired("권한 필요"),
    Error("읽기 실패"),
}

enum class HealthDataCategory(val label: String) {
    Activity("운동·활동"),
    Sleep("수면"),
    Vitals("활력징후"),
    Body("신체·체성분"),
    Nutrition("영양·수분"),
}

data class HealthDataSnapshot(
    val key: String,
    val label: String,
    val category: HealthDataCategory,
    val availability: HealthDataAvailability,
    val recordCount: Int = 0,
    val summary: String,
    val latest: String? = null,
    val sources: List<String> = emptyList(),
    val analysisValues: Map<String, Double> = emptyMap(),
)

data class ExtendedHealthOverview(
    val period: HealthPeriod,
    val snapshots: List<HealthDataSnapshot> = emptyList(),
    val message: String = "Health Connect 수신 현황을 준비하고 있습니다.",
)

data class DerivedHealthResult(
    val validCount: Int,
    val summary: String,
    val analysisValues: Map<String, Double> = emptyMap(),
)

private val localDateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm")

suspend fun readExtendedHealthOverview(
    context: Context,
    period: HealthPeriod,
): ExtendedHealthOverview = withContext(Dispatchers.IO) {
    when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_UNAVAILABLE -> {
            return@withContext ExtendedHealthOverview(
                period = period,
                message = "이 기기에서는 Health Connect를 사용할 수 없습니다.",
            )
        }
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
            return@withContext ExtendedHealthOverview(
                period = period,
                message = "Health Connect 설치 또는 업데이트가 필요합니다.",
            )
        }
    }

    val client = HealthConnectClient.getOrCreate(context)
    val granted = runCatching { client.permissionController.getGrantedPermissions() }
        .getOrElse {
            return@withContext ExtendedHealthOverview(
                period = period,
                message = "Health Connect 권한 상태를 읽지 못했습니다.",
            )
        }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val startDate = when (period) {
        HealthPeriod.Week -> today.minusDays((today.dayOfWeek.value - 1).toLong())
        HealthPeriod.Month -> today.withDayOfMonth(1)
    }
    val filter = TimeRangeFilter.between(startDate.atStartOfDay(zone).toInstant(), Instant.now())

    val snapshots = buildList {
        add(
            client.collectSnapshot(
                key = "exercise",
                label = "운동 세션",
                category = HealthDataCategory.Activity,
                recordType = ExerciseSessionRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val valid = records.mapNotNull { record ->
                    val minutes = Duration.between(record.startTime, record.endTime).toMinutes()
                    minutes.takeIf { it in 1..(24 * 60) }?.let { record.exerciseType to it }
                }
                if (valid.isEmpty()) null else {
                    val totalMinutes = valid.sumOf { it.second }
                    val typeCounts = valid.groupingBy { it.first }.eachCount()
                    val types = valid.groupingBy { exerciseTypeLabel(it.first) }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .joinToString { "${it.key} ${it.value}회" }
                    DerivedHealthResult(
                        validCount = valid.size,
                        summary = "${valid.size}회 · 총 ${formatMinutes(totalMinutes)}${types.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                        analysisValues = buildMap {
                            put("session_count", valid.size.toDouble())
                            put("total_minutes", totalMinutes.toDouble())
                            typeCounts.entries.sortedBy { it.key }.forEach { (type, count) ->
                                put("exercise_type_${type}_sessions", count.toDouble())
                            }
                        },
                    )
                }
            },
        )
        add(
            client.collectSnapshot(
                key = "sleep_stages",
                label = "수면 단계",
                category = HealthDataCategory.Sleep,
                recordType = SleepSessionRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val validSessions = records.filter {
                    Duration.between(it.startTime, it.endTime).toMinutes() in 7..(24 * 60)
                }
                if (validSessions.isEmpty()) null else {
                    val stages = validSessions.flatMap { it.stages }
                    val stageMinutes = stages.groupingBy { it.stage }.fold(0L) { total, stage ->
                        total + Duration.between(stage.startTime, stage.endTime).toMinutes().coerceAtLeast(0)
                    }
                    val awakeMinutes = (stageMinutes[SleepSessionRecord.STAGE_TYPE_AWAKE] ?: 0L) +
                        (stageMinutes[SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED] ?: 0L)
                    val details = buildList {
                        stageMinutes[SleepSessionRecord.STAGE_TYPE_DEEP]?.takeIf { it > 0 }?.let { add("깊은 ${formatMinutes(it)}") }
                        stageMinutes[SleepSessionRecord.STAGE_TYPE_REM]?.takeIf { it > 0 }?.let { add("REM ${formatMinutes(it)}") }
                        stageMinutes[SleepSessionRecord.STAGE_TYPE_LIGHT]?.takeIf { it > 0 }?.let { add("얕은 ${formatMinutes(it)}") }
                        awakeMinutes.takeIf { it > 0 }?.let { add("깨어있음 ${formatMinutes(it)}") }
                    }
                    val total = validSessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                    DerivedHealthResult(
                        validCount = validSessions.size,
                        summary = "${validSessions.size}회 · 총 ${formatMinutes(total)}" +
                            details.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ", separator = " · ").orEmpty(),
                        analysisValues = mapOf(
                            "session_count" to validSessions.size.toDouble(),
                            "total_minutes" to total.toDouble(),
                            "deep_minutes" to (stageMinutes[SleepSessionRecord.STAGE_TYPE_DEEP] ?: 0L).toDouble(),
                            "rem_minutes" to (stageMinutes[SleepSessionRecord.STAGE_TYPE_REM] ?: 0L).toDouble(),
                            "light_minutes" to (stageMinutes[SleepSessionRecord.STAGE_TYPE_LIGHT] ?: 0L).toDouble(),
                            "awake_minutes" to awakeMinutes.toDouble(),
                        ),
                    )
                }
            },
        )
        add(
            client.collectSnapshot(
                key = "oxygen_saturation",
                label = "산소포화도",
                category = HealthDataCategory.Vitals,
                recordType = OxygenSaturationRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeNumbers(records.map { it.percentage.value }.filter { it in 50.0..100.0 }, "%")
            },
        )
        add(
            client.collectSnapshot(
                key = "blood_pressure",
                label = "혈압",
                category = HealthDataCategory.Vitals,
                recordType = BloodPressureRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val valid = records.mapNotNull {
                    val systolic = it.systolic.inMillimetersOfMercury
                    val diastolic = it.diastolic.inMillimetersOfMercury
                    (systolic to diastolic).takeIf {
                        systolic in 60.0..260.0 && diastolic in 30.0..160.0 && diastolic < systolic
                    }
                }
                if (valid.isEmpty()) null else {
                    val systolicMean = valid.map { it.first }.average()
                    val diastolicMean = valid.map { it.second }.average()
                    DerivedHealthResult(
                        valid.size,
                        "평균 ${systolicMean.toInt()}/${diastolicMean.toInt()} mmHg",
                        mapOf(
                            "sample_count" to valid.size.toDouble(),
                            "systolic_mean_mmhg" to systolicMean,
                            "diastolic_mean_mmhg" to diastolicMean,
                        ),
                    )
                }
            },
        )
        add(
            client.collectSnapshot(
                key = "blood_glucose",
                label = "혈당",
                category = HealthDataCategory.Vitals,
                recordType = BloodGlucoseRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeNumbers(records.map { it.level.inMilligramsPerDeciliter }.filter { it in 20.0..600.0 }, "mg/dL")
            },
        )
        add(
            client.collectSnapshot(
                key = "vo2_max",
                label = "최대 산소 섭취량(VO₂ max)",
                category = HealthDataCategory.Vitals,
                recordType = Vo2MaxRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeNumbers(records.map { it.vo2MillilitersPerMinuteKilogram }.filter { it in 5.0..100.0 }, "mL/kg/min")
            },
        )
        add(
            client.collectSnapshot(
                key = "resting_heart_rate",
                label = "안정 시 심박수",
                category = HealthDataCategory.Vitals,
                recordType = RestingHeartRateRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeNumbers(records.map { it.beatsPerMinute.toDouble() }.filter { it in 35.0..220.0 }, "bpm")
            },
        )
        add(
            client.collectSnapshot(
                key = "hrv",
                label = "심박변이도(HRV)",
                category = HealthDataCategory.Vitals,
                recordType = HeartRateVariabilityRmssdRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeNumbers(records.map { it.heartRateVariabilityMillis }.filter { it in 0.1..500.0 }, "ms")
            },
        )
        add(
            client.collectSnapshot(
                key = "respiratory_rate",
                label = "호흡수",
                category = HealthDataCategory.Vitals,
                recordType = RespiratoryRateRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeNumbers(records.map { it.rate }.filter { it in 4.0..60.0 }, "회/분")
            },
        )
        add(
            client.collectSnapshot(
                key = "body_temperature",
                label = "체온",
                category = HealthDataCategory.Vitals,
                recordType = BodyTemperatureRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeNumbers(records.map { it.temperature.inCelsius }.filter { it in 30.0..45.0 }, "°C")
            },
        )
        add(
            client.collectSnapshot(
                key = "skin_temperature",
                label = "피부 온도 변화",
                category = HealthDataCategory.Vitals,
                recordType = SkinTemperatureRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val deltas = records.flatMap { it.deltas }.map { it.delta.inCelsius }.filter { it in -10.0..10.0 }
                if (deltas.isEmpty()) null else DerivedHealthResult(
                    validCount = deltas.size,
                    summary = "기준 대비 평균 ${signedOneDecimal(deltas.average())}°C · 범위 ${signedOneDecimal(deltas.min())}~${signedOneDecimal(deltas.max())}°C",
                    analysisValues = mapOf(
                        "sample_count" to deltas.size.toDouble(),
                        "delta_mean_celsius" to deltas.average(),
                        "delta_minimum_celsius" to deltas.min(),
                        "delta_maximum_celsius" to deltas.max(),
                    ),
                )
            },
        )
        add(
            client.collectSnapshot(
                key = "speed",
                label = "운동 속도",
                category = HealthDataCategory.Activity,
                recordType = SpeedRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.flatMap { it.samples }.map { it.speed.inKilometersPerHour }.filter { it in 0.2..80.0 }
                summarizeNumbers(values, "km/h")
            },
        )
        add(
            client.collectSnapshot(
                key = "power",
                label = "운동 파워",
                category = HealthDataCategory.Activity,
                recordType = PowerRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.flatMap { it.samples }.map { it.power.inWatts }.filter { it in 1.0..3_000.0 }
                summarizeNumbers(values, "W")
            },
        )
        add(
            client.collectSnapshot(
                key = "steps_cadence",
                label = "보행 케이던스",
                category = HealthDataCategory.Activity,
                recordType = StepsCadenceRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.flatMap { it.samples }.map { it.rate }.filter { it in 10.0..300.0 }
                summarizeNumbers(values, "걸음/분")
            },
        )
        add(
            client.collectSnapshot(
                key = "cycling_cadence",
                label = "자전거 케이던스",
                category = HealthDataCategory.Activity,
                recordType = CyclingPedalingCadenceRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.flatMap { it.samples }.map { it.revolutionsPerMinute }.filter { it in 5.0..250.0 }
                summarizeNumbers(values, "rpm")
            },
        )
        add(
            client.collectSnapshot(
                key = "elevation",
                label = "상승 고도",
                category = HealthDataCategory.Activity,
                recordType = ElevationGainedRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.map { it.elevation.inMeters }.filter { it in 0.5..20_000.0 }
                values.takeIf { it.isNotEmpty() }?.let {
                    DerivedHealthResult(
                        it.size,
                        "총 ${"%.0f".format(it.sum())} m",
                        mapOf(
                            "sample_count" to it.size.toDouble(),
                            "total_meters" to it.sum(),
                        ),
                    )
                }
            },
        )
        add(
            client.collectSnapshot(
                key = "floors",
                label = "오른 층수",
                category = HealthDataCategory.Activity,
                recordType = FloorsClimbedRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.map { it.floors }.filter { it in 0.1..10_000.0 }
                values.takeIf { it.isNotEmpty() }?.let {
                    DerivedHealthResult(
                        it.size,
                        "총 ${"%.1f".format(it.sum())}층",
                        mapOf(
                            "sample_count" to it.size.toDouble(),
                            "total_floors" to it.sum(),
                        ),
                    )
                }
            },
        )
        add(
            client.collectSnapshot(
                key = "weight",
                label = "체중",
                category = HealthDataCategory.Body,
                recordType = WeightRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeLatestChange(records.map { it.time to it.weight.inKilograms }.filter { it.second in 2.0..400.0 }, "kg")
            },
        )
        add(
            client.collectSnapshot(
                key = "body_fat",
                label = "체지방률",
                category = HealthDataCategory.Body,
                recordType = BodyFatRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeLatestChange(records.map { it.time to it.percentage.value }.filter { it.second in 1.0..75.0 }, "%")
            },
        )
        add(
            client.collectSnapshot(
                key = "basal_metabolic_rate",
                label = "기초대사량",
                category = HealthDataCategory.Body,
                recordType = BasalMetabolicRateRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.map { it.time to it.basalMetabolicRate.inKilocaloriesPerDay }
                    .filter { it.second in 100.0..10_000.0 }
                summarizeLatestChange(values, "kcal/일")
            },
        )
        add(
            client.collectSnapshot(
                key = "height",
                label = "키",
                category = HealthDataCategory.Body,
                recordType = HeightRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeLatestChange(records.map { it.time to it.height.inMeters * 100.0 }.filter { it.second in 30.0..250.0 }, "cm")
            },
        )
        add(
            client.collectSnapshot(
                key = "body_water",
                label = "체수분량",
                category = HealthDataCategory.Body,
                recordType = BodyWaterMassRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeLatestChange(records.map { it.time to it.mass.inKilograms }.filter { it.second in 0.1..300.0 }, "kg")
            },
        )
        add(
            client.collectSnapshot(
                key = "bone_mass",
                label = "골량",
                category = HealthDataCategory.Body,
                recordType = BoneMassRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeLatestChange(records.map { it.time to it.mass.inKilograms }.filter { it.second in 0.1..30.0 }, "kg")
            },
        )
        add(
            client.collectSnapshot(
                key = "lean_body_mass",
                label = "제지방량",
                category = HealthDataCategory.Body,
                recordType = LeanBodyMassRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                summarizeLatestChange(records.map { it.time to it.mass.inKilograms }.filter { it.second in 1.0..350.0 }, "kg")
            },
        )
        add(
            client.collectSnapshot(
                key = "nutrition",
                label = "영양",
                category = HealthDataCategory.Nutrition,
                recordType = NutritionRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val energy = records.mapNotNull { it.energy?.inKilocalories }.filter { it in 1.0..10_000.0 }.sum()
                val protein = records.mapNotNull { it.protein?.inGrams }.filter { it in 0.1..1_000.0 }.sum()
                val carbohydrate = records.mapNotNull { it.totalCarbohydrate?.inGrams }.filter { it in 0.1..2_000.0 }.sum()
                val fat = records.mapNotNull { it.totalFat?.inGrams }.filter { it in 0.1..1_000.0 }.sum()
                if (energy <= 0.0 && protein <= 0.0 && carbohydrate <= 0.0 && fat <= 0.0) null else {
                    DerivedHealthResult(
                        records.size,
                        "총 ${"%.0f".format(energy)} kcal · 탄수화물 ${"%.0f".format(carbohydrate)}g · 단백질 ${"%.0f".format(protein)}g · 지방 ${"%.0f".format(fat)}g",
                        mapOf(
                            "record_count" to records.size.toDouble(),
                            "energy_kcal" to energy,
                            "carbohydrate_grams" to carbohydrate,
                            "protein_grams" to protein,
                            "fat_grams" to fat,
                        ),
                    )
                }
            },
        )
        add(
            client.collectSnapshot(
                key = "hydration",
                label = "수분 섭취",
                category = HealthDataCategory.Nutrition,
                recordType = HydrationRecord::class,
                granted = granted,
                filter = filter,
            ) { records ->
                val values = records.map { it.volume.inLiters }.filter { it in 0.01..20.0 }
                values.takeIf { it.isNotEmpty() }?.let {
                    DerivedHealthResult(
                        it.size,
                        "총 ${"%.2f".format(it.sum())} L",
                        mapOf(
                            "sample_count" to it.size.toDouble(),
                            "total_liters" to it.sum(),
                        ),
                    )
                }
            },
        )
    }
    val received = snapshots.count { it.availability == HealthDataAvailability.Received }
    val permissionNeeded = snapshots.count { it.availability == HealthDataAvailability.PermissionRequired }
    ExtendedHealthOverview(
        period = period,
        snapshots = snapshots,
        message = buildString {
            append("${snapshots.size}개 유형 중 ${received}개에서 유효한 기록을 확인했습니다.")
            if (permissionNeeded > 0) append(" ${permissionNeeded}개 유형은 읽기 권한이 필요합니다.")
            append(" 0·극소·비정상 범위 값은 요약에서 제외합니다.")
        },
    )
}

private suspend fun <T : Record> HealthConnectClient.collectSnapshot(
    key: String,
    label: String,
    category: HealthDataCategory,
    recordType: KClass<T>,
    granted: Set<String>,
    filter: TimeRangeFilter,
    summarize: (List<T>) -> DerivedHealthResult?,
): HealthDataSnapshot {
    val permission = HealthPermission.getReadPermission(recordType)
    if (permission !in granted) {
        return HealthDataSnapshot(
            key = key,
            label = label,
            category = category,
            availability = HealthDataAvailability.PermissionRequired,
            summary = "Health Connect 읽기 권한을 허용하면 확인할 수 있습니다.",
        )
    }
    val records = try {
        readAllRecords(recordType, filter)
    } catch (_: Throwable) {
        return HealthDataSnapshot(
            key = key,
            label = label,
            category = category,
            availability = HealthDataAvailability.Error,
            summary = "이 유형을 Health Connect에서 읽지 못했습니다.",
        )
    }
    if (records.isEmpty()) {
        return HealthDataSnapshot(
            key = key,
            label = label,
            category = category,
            availability = HealthDataAvailability.NoData,
            summary = "선택한 기간에 전달된 기록이 없습니다.",
        )
    }
    val derived = summarize(records)
        ?: return HealthDataSnapshot(
            key = key,
            label = label,
            category = category,
            availability = HealthDataAvailability.NoData,
            summary = "기록은 있었지만 0·극소·비정상 범위 값이라 계산에서 제외했습니다.",
            sources = sourceLabels(records),
        )
    return HealthDataSnapshot(
        key = key,
        label = label,
        category = category,
        availability = HealthDataAvailability.Received,
        recordCount = derived.validCount,
        summary = derived.summary,
        latest = records.maxOfOrNull { it.metadata.lastModifiedTime }
            ?.atZone(ZoneId.systemDefault())
            ?.format(localDateTimeFormatter),
        sources = sourceLabels(records),
        analysisValues = derived.analysisValues,
    )
}

private suspend fun <T : Record> HealthConnectClient.readAllRecords(
    recordType: KClass<T>,
    filter: TimeRangeFilter,
): List<T> {
    val output = mutableListOf<T>()
    var pageToken: String? = null
    do {
        val response = readRecords(
            ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = filter,
                ascendingOrder = false,
                pageSize = 500,
                pageToken = pageToken,
            ),
        )
        output += response.records
        pageToken = response.pageToken
    } while (!pageToken.isNullOrBlank() && output.size < 5_000)
    return output.take(5_000)
}


private fun sourceLabels(records: List<Record>): List<String> = records
    .map { record ->
        val packageName = record.metadata.dataOrigin.packageName
        val app = when {
            packageName.contains("shealth", ignoreCase = true) -> "Samsung Health"
            packageName.contains("fitbit", ignoreCase = true) -> "Fitbit"
            packageName.contains("google", ignoreCase = true) -> "Google"
            packageName.isBlank() -> "출처 미상"
            else -> packageName.substringAfterLast('.')
        }
        val device = record.metadata.device
        val deviceKind = when (device?.type) {
            Device.TYPE_WATCH -> "워치"
            Device.TYPE_PHONE -> "휴대전화"
            Device.TYPE_SCALE -> "체중계"
            Device.TYPE_RING -> "링"
            Device.TYPE_FITNESS_BAND -> "피트니스 밴드"
            Device.TYPE_CHEST_STRAP -> "가슴 스트랩"
            else -> null
        }
        val model = listOfNotNull(
            device?.manufacturer?.takeIf { it.isNotBlank() },
            device?.model?.takeIf { it.isNotBlank() },
        ).distinct().joinToString(" ")
        listOfNotNull(app, deviceKind, model.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
    .distinct()
    .take(3)

internal fun ExtendedHealthOverview.toPromptContext(): String? {
    val received = snapshots.filter { it.availability == HealthDataAvailability.Received }
    if (received.isEmpty()) return null
    val rows = received.joinToString("\n") { "- ${it.label}: ${it.summary}" }
    return """
        [Health Connect ${period.label} 확장 요약]
        아래 값은 원본 센서 기록이 아니라 앱이 유효 범위를 검사해 만든 기간 요약입니다. 진단이나 단정의 근거로 사용하지 마세요.
        $rows
    """.trimIndent()
}

private fun summarizeNumbers(values: List<Double>, unit: String): DerivedHealthResult? {
    val valid = values.filter { it.isFinite() }
    if (valid.isEmpty()) return null
    return DerivedHealthResult(
        validCount = valid.size,
        summary = "평균 ${"%.1f".format(valid.average())} $unit · 범위 ${"%.1f".format(valid.min())}~${"%.1f".format(valid.max())} $unit",
        analysisValues = mapOf(
            "sample_count" to valid.size.toDouble(),
            "mean" to valid.average(),
            "minimum" to valid.min(),
            "maximum" to valid.max(),
        ),
    )
}

private fun summarizeLatestChange(
    timedValues: List<Pair<Instant, Double>>,
    unit: String,
): DerivedHealthResult? {
    val valid = timedValues.filter { it.second.isFinite() }.sortedBy { it.first }
    if (valid.isEmpty()) return null
    val latest = valid.last().second
    val change = if (valid.size >= 2) latest - valid.first().second else null
    return DerivedHealthResult(
        validCount = valid.size,
        summary = buildString {
            append("최근 ${"%.1f".format(latest)} $unit")
            if (change != null && abs(change) >= 0.05) append(" · 기간 변화 ${signedOneDecimal(change)} $unit")
        },
        analysisValues = buildMap {
            put("sample_count", valid.size.toDouble())
            put("first", valid.first().second)
            put("latest", latest)
            if (change != null) put("change", change)
        },
    )
}

private fun formatMinutes(minutes: Long): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours <= 0 -> "${remainder}분"
        remainder == 0L -> "${hours}시간"
        else -> "${hours}시간 ${remainder}분"
    }
}

private fun signedOneDecimal(value: Double): String = "%+.1f".format(value)

private fun exerciseTypeLabel(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "걷기"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "달리기"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "러닝머신"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "자전거"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "실내 자전거"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "등산"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "수영"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
    -> "근력 운동"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "요가"
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "필라테스"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "고강도 인터벌"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "댄스"
    else -> "기타 운동"
}
