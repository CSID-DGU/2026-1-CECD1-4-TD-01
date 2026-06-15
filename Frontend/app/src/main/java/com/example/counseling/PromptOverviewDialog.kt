package com.example.counseling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class PromptPreviewState(
    val fixedPolicy: String,
    val healthContext: String?,
    val phenotypeContext: String?,
    val galleryContext: String?,
    val memoryContext: String,
)

@Composable
fun PromptOverviewDialog(
    systemPrompt: String,
    preview: PromptPreviewState?,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editablePrompt by remember(systemPrompt) { mutableStateOf(normalizeEditableSystemPrompt(systemPrompt)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프롬프트") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("읽기 전용 고정 정책", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ReadOnlyPromptBlock("상담 안전 / WHO 청소년 활동 기준", preview?.fixedPolicy ?: fixedCounselingPolicy)

                Text("수정 가능", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = editablePrompt,
                    onValueChange = { editablePrompt = it },
                    minLines = 8,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("말투 / 응답 선호") },
                )

                Text("읽기 전용 자동 주입 맥락", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ReadOnlyPromptBlock("기억 / RAG", preview?.memoryContext)
                ReadOnlyPromptBlock("Health", preview?.healthContext)
                ReadOnlyPromptBlock("Phenotype", preview?.phenotypeContext)
                ReadOnlyPromptBlock("Gallery Image Analysis", preview?.galleryContext)
            }
        },
        confirmButton = {
            Button(onClick = { onApply(editablePrompt) }, shape = RoundedCornerShape(8.dp)) {
                Text("적용")
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
private fun ReadOnlyPromptBlock(title: String, content: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = content?.takeIf { it.isNotBlank() } ?: "현재 주입되는 내용이 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
