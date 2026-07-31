package com.example.counseling

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AppModeSettingsDialog(
    role: AppRole,
    developerMode: Boolean,
    themeMode: AppThemeMode,
    onRoleChange: (AppRole) -> Unit,
    onDeveloperModeChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onOpenJetson: () -> Unit,
    onDismiss: () -> Unit,
) {
    var versionTaps by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (developerMode) "앱 설정 · 개발자" else "앱 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("화면 역할", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppRole.entries.forEach { item ->
                        val selected = item == role
                        if (selected) {
                            Button(
                                onClick = { onRoleChange(item) },
                                modifier = Modifier.weight(1f),
                            ) { Text(item.label) }
                        } else {
                            OutlinedButton(
                                onClick = { onRoleChange(item) },
                                modifier = Modifier.weight(1f),
                            ) { Text(item.label) }
                        }
                    }
                }
                Text(
                    if (role == AppRole.Guardian) {
                        "보호자 모드에서 Jetson 위험 알림 감시가 실행됩니다."
                    } else {
                        "사용자 모드에서 상담·건강·생활 화면을 이용합니다."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("테마", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        OutlinedButton(
                            onClick = { onThemeModeChange(mode) },
                            modifier = Modifier.weight(1f),
                            enabled = mode != themeMode,
                        ) { Text(mode.label) }
                    }
                }
                OutlinedButton(onClick = onOpenJetson, modifier = Modifier.fillMaxWidth()) {
                    Text("Jetson 연결 설정")
                }
                if (developerMode) {
                    OutlinedButton(
                        onClick = { onDeveloperModeChange(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("개발자 화면 종료")
                    }
                }
                Text(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${role.label} 화면",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable {
                            versionTaps += 1
                            if (versionTaps >= 5) {
                                versionTaps = 0
                                onDeveloperModeChange(!developerMode)
                            }
                        },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("닫기") }
        },
    )
}
