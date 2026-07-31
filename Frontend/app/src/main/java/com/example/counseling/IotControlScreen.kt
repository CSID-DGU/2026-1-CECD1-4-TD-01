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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun IotControlScreen(onConfigureJetson: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { JetsonSyncSettingsStore(context) }
    val client = remember { JetsonIotClient() }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<IotDevice>>(emptyList()) }
    var configured by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("IoT 장치를 불러오고 있습니다.") }
    var busyDevice by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val token = settings.loadToken()
        if (token.isBlank()) {
            status = "Jetson 연결 설정이 필요합니다."
            return
        }
        runCatching {
            client.listDevices(context, settings.loadEndpoint(), token)
        }.onSuccess {
            devices = it.devices
            configured = it.configured
            status = when {
                !it.configured -> "Jetson에서 Home Assistant 토큰과 장치 목록을 설정해 주세요."
                it.devices.isEmpty() -> "등록된 IoT 장치가 없습니다."
                else -> "장치 ${it.devices.size}개 연결"
            }
        }.onFailure {
            status = it.message ?: "Jetson IoT 연결 실패"
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refresh()
            delay(15_000)
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
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("우리 집 IoT", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { refresh() } },
                            modifier = Modifier.weight(1f),
                        ) { Text("새로고침") }
                        OutlinedButton(
                            onClick = onConfigureJetson,
                            modifier = Modifier.weight(1f),
                        ) { Text("연결 설정") }
                    }
                }
            }
        }
        if (!configured || devices.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "앱은 Jetson의 허용 목록에 등록된 장치만 제어합니다. Home Assistant의 전체 장치 목록이나 토큰은 휴대폰으로 보내지 않습니다.",
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        items(devices, key = IotDevice::id) { device ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${device.kind} · ${if (device.available) device.state else "연결 안 됨"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        device.actions.forEach { action ->
                            OutlinedButton(
                                onClick = {
                                    busyDevice = device.id
                                    scope.launch {
                                        runCatching {
                                            client.command(
                                                context,
                                                settings.loadEndpoint(),
                                                settings.loadToken(),
                                                device.id,
                                                action,
                                            )
                                        }.onSuccess {
                                            status = "${device.name}: ${iotActionLabel(action)} 완료"
                                            delay(600)
                                            refresh()
                                        }.onFailure {
                                            status = it.message ?: "IoT 제어 실패"
                                        }
                                        busyDevice = null
                                    }
                                },
                                enabled = device.available && busyDevice == null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (busyDevice == device.id) "처리 중" else iotActionLabel(action))
                            }
                        }
                    }
                }
            }
        }
    }
}
