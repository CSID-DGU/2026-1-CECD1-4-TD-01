package com.example.counseling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun GuardianScreen(onConfigureJetson: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { JetsonSyncSettingsStore(context) }
    val client = remember { GuardianAlertClient() }
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf<List<GuardianAlert>>(emptyList()) }
    var status by remember { mutableStateOf("Jetson 알림을 확인하고 있습니다.") }
    var busyAlertId by remember { mutableStateOf<String?>(null) }
    var showAcknowledged by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val token = settings.loadToken()
        if (token.isBlank()) {
            status = "Jetson 주소와 인증 토큰을 먼저 연결해 주세요."
            return
        }
        runCatching {
            client.fetch(context, settings.loadEndpoint(), token, afterSequence = 0, limit = 50)
        }.onSuccess {
            alerts = it.alerts.sortedByDescending(GuardianAlert::sequence)
            status = if (alerts.isEmpty()) "최근 위험 알림이 없습니다." else "최근 알림 ${alerts.size}건"
        }.onFailure {
            status = it.message ?: "Jetson 연결에 실패했습니다."
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(10_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("보호자 안심 화면", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { refresh() } }, modifier = Modifier.weight(1f)) {
                            Text("새로고침")
                        }
                        OutlinedButton(onClick = onConfigureJetson, modifier = Modifier.weight(1f)) {
                            Text("Jetson 연결")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = showAcknowledged,
                            onCheckedChange = { showAcknowledged = it },
                        )
                        Text(
                            "처리 완료된 알림도 보기",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        "앱을 닫아도 연결 상태 알림이 표시되는 동안 15초마다 위험 신호를 확인합니다.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        val visibleAlerts = if (showAcknowledged) alerts else alerts.filter { !it.acknowledged }
        if (visibleAlerts.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (alerts.isNotEmpty()) "모든 알림이 처리되었습니다." else "새 위험 신호가 들어오면 여기에 표시됩니다.",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(visibleAlerts, key = GuardianAlert::alertId) { alert ->
            GuardianAlertCard(
                alert = alert,
                busy = busyAlertId == alert.alertId,
                onAcknowledge = {
                    busyAlertId = alert.alertId
                    scope.launch {
                        runCatching {
                            client.acknowledge(
                                context,
                                settings.loadEndpoint(),
                                settings.loadToken(),
                                alert.alertId,
                            )
                        }.onSuccess { refresh() }
                            .onFailure { status = it.message ?: "알림 확인 처리 실패" }
                        busyAlertId = null
                    }
                },
            )
        }
    }
}

@Composable
private fun GuardianAlertCard(
    alert: GuardianAlert,
    busy: Boolean,
    onAcknowledge: () -> Unit,
) {
    val color = when (alert.severity) {
        "CRITICAL" -> Color(0xFFB3261E)
        "HIGH" -> Color(0xFFD84315)
        "CAUTION" -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "${severityLabel(alert.severity)} · ${alert.category}",
                color = color,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(alert.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(alert.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(alert.occurredAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!alert.acknowledged) {
                OutlinedButton(
                    onClick = onAcknowledge,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "처리 중" else "확인했어요")
                }
            } else {
                Text("확인 완료", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

internal fun severityLabel(severity: String): String = when (severity) {
    "CRITICAL" -> "긴급"
    "HIGH" -> "위험"
    "CAUTION" -> "주의"
    else -> "안내"
}
