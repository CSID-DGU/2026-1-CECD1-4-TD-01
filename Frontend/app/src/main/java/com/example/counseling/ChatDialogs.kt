package com.example.counseling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.counseling.llm.EngineStatus

@Composable
fun MemoryDialog(
    defaultMemories: List<String>,
    userMemories: List<String>,
    onDeleteUserMemory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = defaultMemories.map { MemoryItem(it, isDefault = true) } +
        userMemories.map { MemoryItem(it, isDefault = false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("중요 기억") },
        text = {
            LazyColumn(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items) { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (item.isDefault) "기본 기억" else "사용자 기억",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!item.isDefault) {
                                    OutlinedButton(
                                        onClick = { onDeleteUserMemory(item.text) },
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("삭제")
                                    }
                                }
                            }
                            Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (userMemories.isEmpty()) {
                    item {
                        Text(
                            text = "사용자 기억은 아직 없습니다. 대화 중 '이건 중요해', '기억해줘', '저장해줘'처럼 말하면 여기에 저장됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
fun SessionListDialog(
    sessions: List<ChatSessionSummary>,
    currentSessionId: String,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("상담 세션") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sessions.isEmpty()) {
                    Text(
                        "저장된 상담 세션이 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    sessions.take(20).forEach { session ->
                        OutlinedButton(
                            onClick = { onSelectSession(session.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (session.id == currentSessionId) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(session.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${formatSessionTime(session.updatedAt)} · 메시지 ${session.messageCount}개",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onNewSession, shape = RoundedCornerShape(8.dp)) {
                Text("새 세션")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("닫기")
            }
        },
    )
}

@Composable
fun ChatSettingsDialog(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    status: EngineStatus,
    includeHealthContext: Boolean,
    onToggleHealthContext: () -> Unit,
    includePhenotypeContext: Boolean,
    onTogglePhenotypeContext: () -> Unit,
    includeGalleryAnalysisContext: Boolean,
    onToggleGalleryAnalysisContext: () -> Unit,
    healthContextPeriod: HealthPeriod,
    onCycleHealthPeriod: () -> Unit,
    thinkingMode: ThinkingMode,
    onCycleThinkingMode: () -> Unit,
    directAttachmentMode: Boolean,
    onToggleDirectAttachmentMode: () -> Unit,
    chatFontSize: ChatFontSize,
    onChatFontSizeChange: (ChatFontSize) -> Unit,
    onLoadModel: () -> Unit,
    onShowSystemPrompt: () -> Unit,
    onShowMemories: () -> Unit,
    onNewSession: () -> Unit,
    onShowSessions: () -> Unit,
    onExportSession: () -> Unit,
    onImportSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    var conversationOpen by remember { mutableStateOf(false) }
    var responseOpen by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("설정") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    SettingsPlainSectionTitle("모델")
                }
                item {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Button(onClick = onLoadModel, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Text("모델 파일 선택")
                    }
                }

                item { SettingsSectionHeader("대화 관리", conversationOpen) { conversationOpen = !conversationOpen } }
                if (conversationOpen) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsActionButton(onClick = onShowSystemPrompt, modifier = Modifier.weight(1f)) {
                            Text("말투/프롬프트")
                        }
                        SettingsActionButton(onClick = onShowMemories, modifier = Modifier.weight(1f)) {
                            Text("기억")
                        }
                    }
                }
                if (conversationOpen) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsActionButton(onClick = onNewSession, modifier = Modifier.weight(1f)) {
                            Text("새 세션")
                        }
                        SettingsActionButton(onClick = onShowSessions, modifier = Modifier.weight(1f)) {
                            Text("세션 목록")
                        }
                    }
                }
                if (conversationOpen) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsActionButton(onClick = onExportSession, modifier = Modifier.weight(1f)) {
                            Text("내보내기")
                        }
                        SettingsActionButton(onClick = onImportSession, modifier = Modifier.weight(1f)) {
                            Text("불러오기")
                        }
                    }
                }

                item { SettingsSectionHeader("응답 옵션", responseOpen) { responseOpen = !responseOpen } }
                if (responseOpen) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsActionButton(onClick = onToggleHealthContext, modifier = Modifier.weight(1f), selected = includeHealthContext) {
                            Text(if (includeHealthContext) "건강 포함" else "건강 제외")
                        }
                        SettingsActionButton(onClick = onTogglePhenotypeContext, modifier = Modifier.weight(1f), selected = includePhenotypeContext) {
                            Text(if (includePhenotypeContext) "패턴 포함" else "패턴 제외")
                        }
                    }
                }
                if (responseOpen) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsActionButton(
                            onClick = onToggleGalleryAnalysisContext,
                            modifier = Modifier.weight(1f),
                            selected = includeGalleryAnalysisContext,
                        ) {
                            Text(if (includeGalleryAnalysisContext) "Gallery 포함" else "Gallery 제외")
                        }
                        SettingsActionButton(onClick = onCycleHealthPeriod, modifier = Modifier.weight(1f)) {
                            Text("기간 ${healthContextPeriod.label}")
                        }
                        SettingsActionButton(onClick = onCycleThinkingMode, modifier = Modifier.weight(1f)) {
                            Text("사고 ${thinkingMode.label}")
                        }
                    }
                }
                if (responseOpen) item {
                    SettingsActionButton(onClick = onToggleDirectAttachmentMode, modifier = Modifier.fillMaxWidth(), selected = directAttachmentMode) {
                        Text(if (directAttachmentMode) "오디오 직접 분석 켜짐" else "오디오 안전 모드")
                    }
                }

                item {
                    SettingsPlainSectionTitle("표시")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatFontSize.entries.forEach { size ->
                            SettingsActionButton(
                                onClick = { onChatFontSizeChange(size) },
                                modifier = Modifier.weight(1f),
                                selected = size == chatFontSize,
                            ) {
                                Text(size.label)
                            }
                        }
                    }
                }

                item {
                    SettingsPlainSectionTitle("테마")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            SettingsActionButton(
                                onClick = { onThemeModeChange(mode) },
                                modifier = Modifier.weight(1f),
                                selected = mode == themeMode,
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
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
private fun SettingsSectionHeader(title: String, open: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (open) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = if (open) "$title 접기" else "$title 펼치기",
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(if (open) "▲" else "▼")
    }
}

@Composable
private fun SettingsPlainSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}

