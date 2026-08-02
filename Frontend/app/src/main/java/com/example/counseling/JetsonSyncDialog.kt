package com.example.counseling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun JetsonSyncDialog(
    developerMode: Boolean = false,
    connectionOnly: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { JetsonSyncSettingsStore(context.applicationContext) }
    val client = remember { JetsonDerivedInsightClient() }
    val adaptiveClient = remember { JetsonAdaptiveContextClient() }
    val analysisClient = remember { JetsonAnalysisSnapshotClient() }
    val rawDataClient = remember { JetsonRawDataSyncClient() }
    var endpoint by remember { mutableStateOf(settingsStore.loadEndpoint()) }
    var token by remember { mutableStateOf(settingsStore.loadToken()) }
    var availableCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("저장된 파생 정보를 확인하고 있습니다...") }
    var isSending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (connectionOnly) {
            statusMessage = "보호자 알림과 IoT 제어에 사용할 연결 정보를 저장해 주세요."
        } else {
            runCatching {
                collectLatestDerivedInsights(context, refreshLocalSummaries = false)
            }.onSuccess { envelope ->
                availableCategories = envelope.summaries.map { it.category }
                statusMessage = "전송 가능: ${availableCategories.joinToString()}"
            }.onFailure {
                statusMessage = it.message ?: "저장된 파생 정보가 없습니다."
            }
        }
        if (token.isNotBlank()) {
            runCatching {
                adaptiveClient.fetchLearningStatus(context, endpoint, token)
            }.onSuccess { learning ->
                statusMessage = buildString {
                    append("Jetson 연결됨 · ")
                    append(learning.learningState)
                    append(" · 상담 결과 ")
                    append(learning.sessionOutcomes)
                    append("개 · 학습 대기 ")
                    append(learning.pendingOutcomes)
                    append("개 · 정책 v")
                    append(learning.latestPolicyVersion)
                    append(" \u00b7 \ud3b8\uc548 \ubc29\ud5a5 ")
                    append(learning.implicitLikes)
                    append(" \u00b7 \uc911\ub9bd ")
                    append(learning.implicitNeutral)
                    append(" \u00b7 \uaca9\uc591 \ubc29\ud5a5 ")
                    append(learning.implicitDislikes)
                    append(" \u00b7 \uc2a4\ud0c0\uc77c ")
                    append(learning.changedStyleDimensions)
                    append("/")
                    append(learning.styleDimensions)
                    append(" \u00b7 \uad00\ucc30 ")
                    append(learning.styleObservations)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text(if (connectionOnly) "Jetson 연결 설정" else "Jetson 파생 정보 전송") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        if (connectionOnly) {
                            "보호자 알림과 IoT 제어에 Jetson 주소와 인증 토큰만 사용합니다. " +
                                "이 화면에서는 휴대폰의 건강·사진·생활 데이터를 전송하지 않습니다."
                        } else {
                            "사진·음성 파일, 통화번호, 앱 사용 이벤트, 대화 원문은 보내지 않습니다. " +
                                "상담용 버튼은 건강·생활 패턴·Gallery 요약과 최근 음성 감정 결과만 전송합니다. " +
                                if (developerMode) {
                                    "개발자 분석 버튼은 구조화된 수치만 별도 저장합니다."
                                } else ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Jetson 수신 주소") },
                        placeholder = { Text(DEFAULT_JETSON_ENDPOINT) },
                        singleLine = true,
                        enabled = !isSending,
                    )
                }
                item {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("인증 토큰") },
                        singleLine = true,
                        enabled = !isSending,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (developerMode && !connectionOnly) item {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSending && endpoint.isNotBlank() && token.isNotBlank(),
                        onClick = {
                            val saveResult = runCatching {
                                settingsStore.save(endpoint, token)
                            }
                            if (saveResult.isFailure) {
                                statusMessage = "보안 저장 실패: " +
                                    (saveResult.exceptionOrNull()?.message
                                        ?: "Android Keystore 오류")
                            } else {
                                isSending = true
                                statusMessage =
                                    "건강·피노타입·Gallery 구조화 수치를 만들고 있습니다..."
                                scope.launch {
                                    runCatching {
                                        analysisClient.sendLatest(
                                            context = context,
                                            rawEndpoint = endpoint,
                                            token = token,
                                        )
                                    }.onSuccess { result ->
                                        statusMessage = buildString {
                                            append("분석 수치 전송 완료 (HTTP ")
                                            append(result.statusCode)
                                            append(") · ")
                                            append(result.categories.joinToString())
                                            append(" · ")
                                            append(result.payloadBytes)
                                            append(" bytes · ID ")
                                            append(result.snapshotId.take(8))
                                        }
                                    }.onFailure {
                                        statusMessage = "분석 수치 전송 실패: " +
                                            (it.message ?: it.javaClass.simpleName)
                                    }
                                    isSending = false
                                }
                            }
                        },
                    ) {
                        Text("개발자 분석 수치만 수동 전송")
                    }
                }
                if (!connectionOnly) item {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSending && endpoint.isNotBlank() && token.isNotBlank(),
                        onClick = {
                            val saveResult = runCatching {
                                settingsStore.save(endpoint, token)
                            }
                            if (saveResult.isFailure) {
                                statusMessage = "보안 저장 실패: " +
                                    (saveResult.exceptionOrNull()?.message
                                        ?: "Android Keystore 오류")
                            } else {
                                isSending = true
                                statusMessage = "원본 데이터를 수집하여 전송하고 있습니다..."
                                scope.launch {
                                    val healthSummary = runCatching { readHealthSummary(context, HealthPeriod.Week) }.getOrNull()
                                    val healthOverview = runCatching { readExtendedHealthOverview(context, HealthPeriod.Week) }.getOrNull()
                                    val callSummary = runCatching { com.psychocare.phenotype.CallLogAnalyzer(context.applicationContext).analyze() }.getOrNull()
                                    val appUsageSummary = runCatching { com.psychocare.phenotype.AppUsageAnalyzer(context.applicationContext).analyze() }.getOrNull()
                                    val calendarSummary = runCatching { com.psychocare.phenotype.CalendarAnalyzer(context.applicationContext).analyze() }.getOrNull()
                                    val rawExerciseEntries = runCatching { readRawExerciseRecords(context) }.getOrNull()
                                    val rawSleepSessions = runCatching { readRawSleepSessions(context) }.getOrNull()
                                    
                                    rawDataClient.sendRawData(
                                        context,
                                        healthSummary,
                                        healthOverview,
                                        callSummary,
                                        appUsageSummary,
                                        null,
                                        calendarSummary,
                                        rawExerciseEntries,
                                        rawSleepSessions
                                    ).onSuccess {
                                        statusMessage = "원본 데이터 전송 완료"
                                    }.onFailure {
                                        statusMessage = "원본 데이터 전송 실패: ${it.message}"
                                    }
                                    isSending = false
                                }
                            }
                        },
                    ) {
                        Text("모든 원본 데이터 전송 (위험)", color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isSending) CircularProgressIndicator()
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!connectionOnly) {
                            Text(
                                "\ud604\uc7ac \uac10\uc815\uc740 \ub2e4\uc74c \ub2f5\ubcc0 \uc815\ucc45\uc5d0 \uc989\uc2dc \ubc18\uc601\ub429\ub2c8\ub2e4. " +
                                    "\ub2f5\ubcc0 \ud6c4 \ud3b8\uc548\ud574\uc9c0\uba74 \uc554\ubb35\uc801 \uc88b\uc544\uc694, \uaca9\uc591\ub418\uba74 \uc554\ubb35\uc801 \uc2eb\uc5b4\uc694\ub85c \uc9d1\uacc4\ud574 " +
                                    "\uc57c\uac04 \ud559\uc2b5\uc5d0 \ubc18\uc601\ud569\ub2c8\ub2e4. \uc804\ub7b5 \uc21c\uc704\uc640 \ub2f5\ubcc0 \uae38\uc774\u00b7\uacf5\uac10\u00b7\uc9c8\ubb38\u00b7\uc870\uc5b8 \ub4f1 10\uac1c \uc2a4\ud0c0\uc77c \uc218\uce58\uac00 \uac1c\uc778\ud654\ub418\uba70, \ubaa8\ub378 \ubcf8\uccb4 \uac00\uc911\uce58\ub97c \uc7ac\ud559\uc2b5\ud558\uc9c0\ub294 \uc54a\uc2b5\ub2c8\ub2e4.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "iot.local이 해석되지 않으면 개발자 빌드에서 Jetson 사설 IP 또는 " +
                                "Tailscale IP(예: http://100.93.236.68:8765)를 입력하세요. " +
                                "공인 IP의 평문 http는 계속 차단합니다. 주소는 앱 설정에, 인증 토큰은 " +
                                "Android Keystore 암호화 키로 보호해 저장합니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSending && endpoint.isNotBlank() && token.isNotBlank(),
                onClick = {
                    val saveResult = runCatching { settingsStore.save(endpoint, token) }
                    if (saveResult.isFailure) {
                        statusMessage = "보안 저장 실패: ${saveResult.exceptionOrNull()?.message ?: "Android Keystore 오류"}"
                    } else if (connectionOnly) {
                        statusMessage = "Jetson 연결 정보를 안전하게 저장했습니다."
                    } else {
                        isSending = true
                        statusMessage = "로컬에서 최신 요약을 만든 뒤 Jetson으로 전송하고 있습니다..."
                        scope.launch {
                            runCatching {
                                client.sendLatest(context, endpoint, token)
                            }.onSuccess { result ->
                                availableCategories = result.sentCategories
                                val learning = runCatching {
                                    adaptiveClient.fetchLearningStatus(
                                        context,
                                        endpoint,
                                        token,
                                    )
                                }.getOrNull()
                                statusMessage = buildString {
                                    append("전송 완료 (HTTP ${result.statusCode}): ")
                                    append(result.sentCategories.joinToString())
                                    learning?.let {
                                        append(" · 학습 ${it.learningState}")
                                        append(" · 결과 ${it.sessionOutcomes}개")
                                        append(" · 대기 ${it.pendingOutcomes}개")
                                        append(" · 정책 v${it.latestPolicyVersion}")
                                        append(" \u00b7 \ud3b8\uc548 ${it.implicitLikes}")
                                        append(" \u00b7 \uc911\ub9bd ${it.implicitNeutral}")
                                        append(" \u00b7 \uaca9\uc591 ${it.implicitDislikes}")
                                        append(" \u00b7 \uc2a4\ud0c0\uc77c ${it.changedStyleDimensions}/${it.styleDimensions}")
                                        append(" \u00b7 \uad00\ucc30 ${it.styleObservations}")
                                    }
                                }
                            }.onFailure {
                                statusMessage = "전송 실패: ${it.message ?: it.javaClass.simpleName}"
                            }
                            isSending = false
                        }
                    }
                },
            ) {
                Text(if (connectionOnly) "연결 정보 저장" else "상담용 파생 요약 전송")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !isSending) {
                Text("닫기")
            }
        },
    )
}
