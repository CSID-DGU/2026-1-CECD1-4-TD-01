package com.example.counseling

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val YouthDailyActivityGoalMinutes = 60
private const val ModerateWalkingStepsPerMinute = 100

@Composable
fun HealthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPeriod by remember { mutableStateOf(HealthPeriod.Week) }
    var selectedDay by remember { mutableStateOf<HealthDaySummary?>(null) }
    var summary by remember {
        mutableStateOf(
            HealthSummary(
                period = selectedPeriod,
                message = "Health Connect 권한을 허용하면 이번 주 또는 이번 달 건강 데이터를 볼 수 있습니다.",
            ),
        )
    }
    var extendedOverview by remember {
        mutableStateOf(ExtendedHealthOverview(period = selectedPeriod))
    }
    var isLoading by remember { mutableStateOf(false) }

    fun loadHealthData(period: HealthPeriod = selectedPeriod) {
        scope.launch {
            isLoading = true
            summary = readHealthSummary(context, period)
            extendedOverview = readExtendedHealthOverview(context, period)
            isLoading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
        onResult = {
            loadHealthData()
        },
    )

    LaunchedEffect(selectedPeriod) {
        loadHealthData(selectedPeriod)
    }

    selectedDay?.let { day ->
        HealthDayDetailDialog(
            day = day,
            onDismiss = { selectedDay = null },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            when (HealthConnectClient.getSdkStatus(context)) {
                                HealthConnectClient.SDK_AVAILABLE -> permissionLauncher.launch(allHealthPermissions)
                                HealthConnectClient.SDK_UNAVAILABLE -> {
                                    summary = summary.copy(message = "이 기기에서는 Health Connect를 사용할 수 없습니다.")
                                }
                                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                                    summary = summary.copy(message = "Health Connect 설치 또는 업데이트가 필요합니다.")
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("권한 연결")
                }
                OutlinedButton(
                    onClick = { loadHealthData() },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("새로고침")
                }
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { selectedPeriod = HealthPeriod.Week },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedPeriod == HealthPeriod.Week) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selectedPeriod == HealthPeriod.Week) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("주")
                }
                Button(
                    onClick = { selectedPeriod = HealthPeriod.Month },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedPeriod == HealthPeriod.Month) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selectedPeriod == HealthPeriod.Month) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("월")
                }
            }
        }
        item {
            YouthActivityGoalCard(summary = summary)
        }
        item {
            HealthCalendarCard(
                summary = summary,
                onSelectDay = { selectedDay = it },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthMetric("걸음", summary.formattedMetric(HealthMetricKind.Steps), modifier = Modifier.weight(1f))
                HealthMetric("거리", summary.formattedMetric(HealthMetricKind.Distance), modifier = Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthMetric("활동 칼로리", summary.formattedMetric(HealthMetricKind.ActiveCalories), modifier = Modifier.weight(1f))
                HealthMetric("수면", summary.formattedMetric(HealthMetricKind.Sleep), modifier = Modifier.weight(1f))
            }
        }
        item {
            Text(
                text = summary.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ExtendedHealthOverviewSection(overview = extendedOverview)
        }
    }
}

@Composable
fun ExtendedHealthOverviewSection(overview: ExtendedHealthOverview) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Health Connect 수신 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    overview.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "출처·기기 정보는 이 화면에서만 확인합니다. 운동 GPS 경로와 원본 센서 배열은 읽거나 Jetson으로 보내지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        overview.snapshots.groupBy { it.category }.forEach { (category, snapshots) ->
            Text(category.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            snapshots.forEach { snapshot ->
                HealthDataSnapshotCard(snapshot)
            }
        }
    }
}

@Composable
private fun HealthDataSnapshotCard(snapshot: HealthDataSnapshot) {
    val statusColor = when (snapshot.availability) {
        HealthDataAvailability.Received -> MaterialTheme.colorScheme.primary
        HealthDataAvailability.NoData -> MaterialTheme.colorScheme.onSurfaceVariant
        HealthDataAvailability.PermissionRequired -> MaterialTheme.colorScheme.tertiary
        HealthDataAvailability.Error -> MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snapshot.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (snapshot.availability == HealthDataAvailability.Received) {
                        "${snapshot.availability.label} ${snapshot.recordCount}건"
                    } else {
                        snapshot.availability.label
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(snapshot.summary, style = MaterialTheme.typography.bodyMedium)
            val details = buildList {
                snapshot.latest?.let { add("최근 동기화/수정 $it") }
                snapshot.sources.takeIf { it.isNotEmpty() }?.let { add("출처 ${it.joinToString()}") }
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun YouthActivityGoalCard(summary: HealthSummary) {
    val days = summary.daily
    val validDays = days.filter { it.hasValidMetric(HealthMetricKind.Steps) }
    val activityMinutes = validDays.mapNotNull { it.estimatedActivityMinutes() }
    val achievedDays = activityMinutes.count { it >= YouthDailyActivityGoalMinutes }
    val averageMinutes = activityMinutes.takeIf { it.isNotEmpty() }?.average()
    val progress = if (validDays.isEmpty()) 0f else (achievedDays.toFloat() / validDays.size).coerceIn(0f, 1f)

    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("청소년 활동 목표", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "WHO 아동·청소년 권고를 기준으로 하루 60분 이상의 중강도 이상 활동을 목표로 봅니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (validDays.isEmpty()) "기록 없음" else "${achievedDays}/${validDays.size}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (validDays.isNotEmpty()) {
                    Text("일 달성", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                if (averageMinutes == null) {
                    "0 또는 누락된 날은 목표 계산에서 제외됩니다."
                } else {
                    "평균 ${averageMinutes.toInt()}분/일 · 유효한 걸음 기록 ${validDays.size}일 기준"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun HealthCalendarCard(summary: HealthSummary, onSelectDay: (HealthDaySummary) -> Unit) {
    val dailyByDate = summary.daily.associateBy { it.date }
    val days = calendarDaysFor(summary)

    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("활동 달성 달력", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "각 날짜를 누르면 세부 기록을 볼 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "목표 ${YouthDailyActivityGoalMinutes}분",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("월", "화", "수", "목", "금", "토", "일").forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            days.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        val day = dailyByDate[date]
                        HealthCalendarCell(
                            date = date,
                            day = day,
                            inPeriod = date inPeriodOf summary,
                            onClick = { if (day != null) onSelectDay(day) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HealthCalendarCell(
    date: LocalDate,
    day: HealthDaySummary?,
    inPeriod: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val minutes = day?.estimatedActivityMinutes()
    val achieved = minutes != null && minutes >= YouthDailyActivityGoalMinutes
    val cellColor = when {
        !inPeriod -> MaterialTheme.colorScheme.surface
        achieved -> MaterialTheme.colorScheme.primaryContainer
        minutes != null -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (inPeriod) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cellColor,
        modifier = modifier
            .aspectRatio(0.86f)
            .clickable(enabled = day != null, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (achieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    ),
            )
            Text(
                when {
                    day == null -> ""
                    minutes == null -> "기록 없음"
                    else -> "${minutes}분"
                },
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun HealthMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun HealthDayCard(day: HealthDaySummary, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = day.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "걸음 ${day.formattedMetric(HealthMetricKind.Steps)} · 거리 ${day.formattedMetric(HealthMetricKind.Distance)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "총 ${day.formattedMetric(HealthMetricKind.Calories)} · 활동 ${day.formattedMetric(HealthMetricKind.ActiveCalories)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "심박 ${day.formattedMetric(HealthMetricKind.HeartRate)} · 수면 ${day.formattedMetric(HealthMetricKind.Sleep)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun HealthDayDetailDialog(day: HealthDaySummary, onDismiss: () -> Unit) {
    val minutes = day.estimatedActivityMinutes()
    val achieved = minutes != null && minutes >= YouthDailyActivityGoalMinutes
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(day.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (achieved) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when {
                                minutes == null -> "활동 기록 없음"
                                achieved -> "목표 달성"
                                else -> "목표 미달"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (minutes == null) {
                                "0 또는 누락된 걸음 값은 목표 계산에 포함하지 않았습니다."
                            } else {
                                "추정 활동 ${minutes}분 / 목표 ${YouthDailyActivityGoalMinutes}분"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                HealthDetailRow("걸음 수", day.formattedMetric(HealthMetricKind.Steps))
                HealthDetailRow("총 소모 칼로리", day.formattedMetric(HealthMetricKind.Calories))
                HealthDetailRow("활동 칼로리", day.formattedMetric(HealthMetricKind.ActiveCalories))
                HealthDetailRow("이동 거리", day.formattedMetric(HealthMetricKind.Distance))
                HealthDetailRow("평균 심박수", day.formattedMetric(HealthMetricKind.HeartRate))
                HealthDetailRow("수면 시간", day.formattedMetric(HealthMetricKind.Sleep))
                if (day.exclusions.isNotEmpty()) {
                    Text(
                        "계산 제외: " + day.exclusions.joinToString { "${it.metric.label}(${it.reason})" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("닫기")
            }
        },
    )
}

@Composable
fun HealthDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun HealthDaySummary.estimatedActivityMinutes(): Int? {
    if (!hasValidMetric(HealthMetricKind.Steps)) return null
    return (steps / ModerateWalkingStepsPerMinute).toInt().coerceAtLeast(0)
}

private fun HealthSummary.formattedMetric(metric: HealthMetricKind): String {
    if (!hasValidMetric(metric)) return "기록 없음"
    return when (metric) {
        HealthMetricKind.Steps -> "%,d".format(steps)
        HealthMetricKind.Calories -> "%,.1f kcal".format(caloriesKcal)
        HealthMetricKind.ActiveCalories -> "%,.0f kcal".format(activeCaloriesKcal)
        HealthMetricKind.Distance -> "%,.1f km".format(distanceKm)
        HealthMetricKind.HeartRate -> heartRateBpm?.let { "%d bpm".format(it) } ?: "기록 없음"
        HealthMetricKind.Sleep -> "%,.1f 시간".format(sleepHours)
    }
}

private fun HealthDaySummary.formattedMetric(metric: HealthMetricKind): String {
    if (!hasValidMetric(metric)) return "기록 없음"
    return when (metric) {
        HealthMetricKind.Steps -> "%,d".format(steps)
        HealthMetricKind.Calories -> "%,.1f kcal".format(caloriesKcal)
        HealthMetricKind.ActiveCalories -> "%,.1f kcal".format(activeCaloriesKcal)
        HealthMetricKind.Distance -> "%,.2f km".format(distanceKm)
        HealthMetricKind.HeartRate -> heartRateBpm?.let { "%d bpm".format(it) } ?: "기록 없음"
        HealthMetricKind.Sleep -> "%,.1f 시간".format(sleepHours)
    }
}

private fun calendarDaysFor(summary: HealthSummary): List<LocalDate> {
    val today = LocalDate.now()
    val start = when (summary.period) {
        HealthPeriod.Week -> today.with(DayOfWeek.MONDAY)
        HealthPeriod.Month -> today.withDayOfMonth(1)
    }
    val calendarStart = start.with(DayOfWeek.MONDAY)
    val calendarEnd = today.with(DayOfWeek.SUNDAY)
    return buildList {
        var date = calendarStart
        while (!date.isAfter(calendarEnd)) {
            add(date)
            date = date.plusDays(1)
        }
    }
}

private infix fun LocalDate.inPeriodOf(summary: HealthSummary): Boolean {
    val today = LocalDate.now()
    val start = when (summary.period) {
        HealthPeriod.Week -> today.with(DayOfWeek.MONDAY)
        HealthPeriod.Month -> today.withDayOfMonth(1)
    }
    return !isBefore(start) && !isAfter(today)
}
