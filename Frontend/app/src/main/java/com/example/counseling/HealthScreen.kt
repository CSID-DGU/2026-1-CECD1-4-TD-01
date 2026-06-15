package com.example.counseling

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

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
                    enabled = selectedPeriod != HealthPeriod.Week && !isLoading,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("주")
                }
                Button(
                    onClick = { selectedPeriod = HealthPeriod.Month },
                    enabled = selectedPeriod != HealthPeriod.Month && !isLoading,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("월")
                }
            }
        }
        item { Text("${summary.period.label} 요약", style = MaterialTheme.typography.titleMedium) }
        item { HealthMetric("걸음 수", "%,d".format(summary.steps)) }
        item { HealthMetric("총 소모 칼로리", "%,.1f kcal".format(summary.caloriesKcal)) }
        item { HealthMetric("활동 칼로리", "%,.1f kcal".format(summary.activeCaloriesKcal)) }
        item { HealthMetric("이동 거리", "%,.2f km".format(summary.distanceKm)) }
        item { HealthMetric("평균 심박수", summary.heartRateBpm?.let { "%d bpm".format(it) } ?: "데이터 없음") }
        item { HealthMetric("수면 시간", "%,.1f 시간".format(summary.sleepHours)) }
        item { Text("날짜별 기록", style = MaterialTheme.typography.titleMedium) }
        items(summary.daily) { day ->
            HealthDayCard(day = day, onClick = { selectedDay = day })
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
fun HealthMetric(label: String, value: String) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(day.date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
