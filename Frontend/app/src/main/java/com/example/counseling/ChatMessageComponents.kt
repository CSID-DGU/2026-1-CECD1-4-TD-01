package com.example.counseling

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole

@Composable
fun CompactActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(text)
    }
}

@Composable
fun StatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(8.dp),
                content = {},
            )
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, fontSize: ChatFontSize = ChatFontSize.Normal) {
    val isUser = message.role == ChatRole.User
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = if (isUser) 0.dp else 1.dp,
            shadowElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.82f else 0.94f)
                .border(
                    width = 1.dp,
                    color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                Text(
                    text = if (isUser) "나" else "상담사",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = markdownText(message.content),
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = fontSize.sizeSp.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (message.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        ) {
                            Text("복사")
                        }
                    }
                }
                if (message.attachmentLabel != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = message.attachmentLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

