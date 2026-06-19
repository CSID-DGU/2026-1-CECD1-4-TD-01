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
    var isLoading by remember { mutableStateOf(false) }

    fun loadHealthData(period: HealthPeriod = selectedPeriod) {
        scope.launch {
            isLoading = true
            summary = readHealthSummary(context, period)
            isLoading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
        onResult = { granted ->
            if (granted.containsAll(healthPermissions)) loadHealthData()
            else summary = summary.copy(message = "건강 데이터 권한이 모두 허용되지 않았습니다.")
        },
    )

    LaunchedEffect(Unit) {
        summary = readHealthSummary(context, selectedPeriod)
    }

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
                                HealthConnectClient.SDK_AVAILABLE -> permissionLauncher.launch(healthPermissions)
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
                HealthMetric("걸음", "%,d".format(summary.steps), modifier = Modifier.weight(1f))
                HealthMetric("거리", "%,.1f km".format(summary.distanceKm), modifier = Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthMetric("활동 칼로리", "%,.0f kcal".format(summary.activeCaloriesKcal), modifier = Modifier.weight(1f))
                HealthMetric("수면", "%,.1f 시간".format(summary.sleepHours), modifier = Modifier.weight(1f))
            }
        }
        item {
            Text(
                text = summary.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun YouthActivityGoalCard(summary: HealthSummary) {
    val days = summary.daily
    val achievedDays = days.count { it.estimatedActivityMinutes() >= YouthDailyActivityGoalMinutes }
    val averageMinutes = days.takeIf { it.isNotEmpty() }?.map { it.estimatedActivityMinutes() }?.average() ?: 0.0
    val progress = if (days.isEmpty()) 0f else (achievedDays.toFloat() / days.size).coerceIn(0f, 1f)

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
                    "${achievedDays}/${days.size}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("일 달성", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "평균 ${averageMinutes.toInt()}분/일 · 걸음 수 기반 추정치",
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
    val minutes = day?.estimatedActivityMinutes() ?: 0
    val achieved = minutes >= YouthDailyActivityGoalMinutes
    val cellColor = when {
        !inPeriod -> MaterialTheme.colorScheme.surface
        achieved -> MaterialTheme.colorScheme.primaryContainer
        minutes > 0 -> MaterialTheme.colorScheme.secondaryContainer
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
                if (day == null) "" else "${minutes}분",
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
                text = "걸음 %,d · 거리 %,.2f km".format(day.steps, day.distanceKm),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "총 %,.1f kcal · 활동 %,.1f kcal".format(day.caloriesKcal, day.activeCaloriesKcal),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "심박 ${day.heartRateBpm?.let { "%d bpm".format(it) } ?: "데이터 없음"} · 수면 %,.1f 시간".format(day.sleepHours),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun HealthDayDetailDialog(day: HealthDaySummary, onDismiss: () -> Unit) {
    val minutes = day.estimatedActivityMinutes()
    val achieved = minutes >= YouthDailyActivityGoalMinutes
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
                            if (achieved) "목표 달성" else "목표 미달",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "추정 활동 ${minutes}분 / 목표 ${YouthDailyActivityGoalMinutes}분",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                HealthDetailRow("걸음 수", "%,d".format(day.steps))
                HealthDetailRow("총 소모 칼로리", "%,.1f kcal".format(day.caloriesKcal))
                HealthDetailRow("활동 칼로리", "%,.1f kcal".format(day.activeCaloriesKcal))
                HealthDetailRow("이동 거리", "%,.2f km".format(day.distanceKm))
                HealthDetailRow("평균 심박수", day.heartRateBpm?.let { "%d bpm".format(it) } ?: "데이터 없음")
                HealthDetailRow("수면 시간", "%,.1f 시간".format(day.sleepHours))
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

private fun HealthDaySummary.estimatedActivityMinutes(): Int {
    return (steps / ModerateWalkingStepsPerMinute).toInt().coerceAtLeast(0)
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
