package com.example.counseling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

@Composable
fun MessageInput2(
    value: String,
    enabled: Boolean,
    attachmentLabel: String?,
    onValueChange: (String) -> Unit,
    thinkingMode: ThinkingMode,
    onCycleThinkingMode: () -> Unit,
    onRecordAudio: () -> Unit,
    onSpeechInput: () -> Unit,
    onClearAttachment: () -> Unit,
    onSend: () -> Unit,
    isRecordingAudio: Boolean,
    isThinking: Boolean,
    imeBottomPadding: Dp,
    fontSize: ChatFontSize,
    modifier: Modifier = Modifier,
) {
    var showAttachmentMenu by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(
                start = 14.dp,
                top = 12.dp,
                end = 14.dp,
                bottom = 14.dp + if (imeBottomPadding == Dp.Unspecified) 0.dp else imeBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (attachmentLabel != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            attachmentLabel,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = onClearAttachment) {
                            Text("제거")
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    OutlinedButton(
                        onClick = { showAttachmentMenu = true },
                        enabled = enabled || isRecordingAudio,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Text("+")
                    }
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("말하기") },
                            onClick = {
                                showAttachmentMenu = false
                                onSpeechInput()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isRecordingAudio) "녹음 정지" else "녹음 시작") },
                            onClick = {
                                showAttachmentMenu = false
                                onRecordAudio()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("사고 모드: ${thinkingMode.next().label}") },
                            onClick = {
                                showAttachmentMenu = false
                                onCycleThinkingMode()
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    minLines = 1,
                    maxLines = 5,
                    textStyle = TextStyle(fontSize = fontSize.sizeSp.sp),
                    shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("메시지를 입력하세요") },
                )
                Button(
                    onClick = onSend,
                    enabled = enabled && !isRecordingAudio && !isThinking && (value.isNotBlank() || attachmentLabel != null),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("전송")
                }
            }
        }
    }
}

@Composable
fun MessageInput(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp, modifier = modifier) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                minLines = 1,
                maxLines = 5,
                placeholder = { Text("메시지를 입력하세요") },
            )
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("전송")
            }
        }
    }
}

fun markdownText(value: String): AnnotatedString {
    if (!value.contains("**")) return AnnotatedString(value)

    return buildAnnotatedString {
        var index = 0
        var bold = false
        while (index < value.length) {
            val next = value.indexOf("**", startIndex = index)
            if (next < 0) {
                appendStyledMarkdownPart(value.substring(index), bold)
                break
            }
            appendStyledMarkdownPart(value.substring(index, next), bold)
            bold = !bold
            index = next + 2
        }
    }
}

fun AnnotatedString.Builder.appendStyledMarkdownPart(text: String, bold: Boolean) {
    if (text.isEmpty()) return
    if (bold) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text)
        }
    } else {
        append(text)
    }
}

