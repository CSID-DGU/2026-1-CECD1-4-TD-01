package com.example.counseling

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    presentationMode: Boolean,
    compactControls: Boolean,
    chromeHidden: Boolean,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
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
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    minLines = 1,
                    maxLines = 5,
                    textStyle = TextStyle(fontSize = fontSize.sizeSp.sp),
                    shape = RoundedCornerShape(8.dp),
                    placeholder = {
                        Text(if (presentationMode) "지금 마음에 남아 있는 일을 적어 주세요" else "메시지를 입력하세요")
                    },
                )
                OutlinedButton(
                    onClick = onToggleChrome,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.widthIn(min = 70.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (chromeHidden) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (chromeHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(if (chromeHidden) "UI 표시" else "UI 숨김")
                }
                Button(
                    onClick = onSend,
                    enabled = enabled && !isRecordingAudio && !isThinking && (value.isNotBlank() || attachmentLabel != null),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.widthIn(min = 72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    ),
                ) {
                    Text("전송")
                }
            }
            if (!compactControls) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onSpeechInput,
                        enabled = enabled,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        ),
                    ) {
                        Text("말하기")
                    }
                    OutlinedButton(
                        onClick = onRecordAudio,
                        enabled = enabled || isRecordingAudio,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isRecordingAudio) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isRecordingAudio) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        ),
                    ) {
                        Text(if (isRecordingAudio) "녹음 종료" else "녹음")
                    }
                    if (!presentationMode) {
                        OutlinedButton(
                            onClick = onCycleThinkingMode,
                            enabled = enabled,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(thinkingMode.label)
                        }
                    }
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

