package com.example.counseling

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import com.example.counseling.llm.EngineStatus
import com.example.counseling.llm.LiteRtLmCounselingEngine
import com.example.counseling.voiceemotion.LiteRtVoiceEmotionAnalyzer
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun ChatScreen(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    settingsOpenRequests: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liteRtEngine = remember { LiteRtLmCounselingEngine(context.applicationContext) }
    val voiceEmotionAnalyzer = remember { LiteRtVoiceEmotionAnalyzer(context.applicationContext) }
    val sessionStore = remember { ChatSessionStore(context.applicationContext) }
    val memoryStore = remember { ChatMemoryStore(context.applicationContext) }
    val imeBottomPadding = 0.dp
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                role = ChatRole.Assistant,
                content = "모델 로드를 눌러 gemma-4-E4B-it.litertlm 파일을 선택해 주세요. 로드가 끝나면 바로 대화할 수 있습니다.",
            ),
        )
    }
    val importantMemories = remember { mutableStateListOf<String>() }

    var status by remember { mutableStateOf(EngineStatus(false, "모델 파일을 선택해 주세요.")) }
    var input by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf(defaultUserCustomInstruction) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var showPromptOverview by remember { mutableStateOf(false) }
    var promptPreview by remember { mutableStateOf<PromptPreviewState?>(null) }
    var showMemories by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var attachedAudioPath by remember { mutableStateOf<String?>(null) }
    var attachmentLabel by remember { mutableStateOf<String?>(null) }
    var thinkingMode by remember { mutableStateOf(ThinkingMode.Auto) }
    var includeHealthContext by remember { mutableStateOf(false) }
    var includePhenotypeContext by remember { mutableStateOf(false) }
    var includeGalleryAnalysisContext by remember { mutableStateOf(false) }
    var healthContextPeriod by remember { mutableStateOf(HealthPeriod.Week) }
    var directAttachmentMode by remember { mutableStateOf(true) }
    var chatFontSize by remember { mutableStateOf(ChatFontSize.Normal) }
    var currentSessionId by remember { mutableStateOf(ChatMemoryStore.DEFAULT_SESSION_ID) }
    var showSessionList by remember { mutableStateOf(false) }
    var sessionSummaries by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    var isThinking by remember { mutableStateOf(false) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var activeAudioRecording by remember { mutableStateOf<ActiveAudioRecording?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var isLoadingModel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun loadSelectedModel(uri: Uri) {
        isLoadingModel = true
        status = EngineStatus(false, "모델을 앱 저장소로 복사하고 있습니다...")
        scope.launch {
            status = liteRtEngine.importAndLoadModel(uri, buildSystemPromptWithMemories(systemPrompt, importantMemories))
            isLoadingModel = false
            messages += ChatMessage(ChatRole.Assistant, status.message)
        }
    }

    fun loadPromptPreview() {
        promptPreview = PromptPreviewState(
            fixedPolicy = fixedCounselingPolicy,
            healthContext = "불러오는 중...",
            phenotypeContext = "불러오는 중...",
            galleryContext = "불러오는 중...",
            memoryContext = "불러오는 중...",
        )
        scope.launch {
            val healthContext = if (includeHealthContext) {
                refreshHealthRagSlot(context, healthContextPeriod)
            } else {
                null
            }
            val phenotypeContext = if (includePhenotypeContext) {
                refreshPhenotypeRagSlot(context).contextText
            } else {
                null
            }
            val galleryContext = if (includeGalleryAnalysisContext) {
                readGalleryAnalysisPromptContext(context).contextText
            } else {
                null
            }
            val memoryContext = buildString {
                appendLine("[기본 중요 기억]")
                defaultImportantMemories.forEach { appendLine("- $it") }
                if (importantMemories.isNotEmpty()) {
                    appendLine()
                    appendLine("[사용자가 중요하다고 저장한 개인 맥락]")
                    importantMemories.takeLast(20).forEach { appendLine("- $it") }
                }
            }.trim()
            promptPreview = PromptPreviewState(
                fixedPolicy = fixedCounselingPolicy,
                healthContext = healthContext,
                phenotypeContext = phenotypeContext,
                galleryContext = galleryContext,
                memoryContext = memoryContext,
            )
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) loadSelectedModel(uri) },
    )

    val speechInput = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    input = if (input.isBlank()) spoken else "$input $spoken"
                }
            }
        },
    )

    fun stopRecordingAndAttach() {
        val recording = activeAudioRecording ?: return
        scope.launch {
            runCatching {
                recording.recorder.stop()
                recording.job.join()
                recording.recorder.release()
                require(recording.file.length() > 44L) { "녹음 파일이 비어 있습니다." }
                attachedAudioPath = recording.file.absolutePath
            }.onSuccess {
                attachmentLabel = "WAV 음성 녹음 첨부"
                status = EngineStatus(status.isModelLoaded, "WAV 녹음을 첨부했습니다.")
            }.onFailure {
                runCatching { recording.recorder.release() }
                recording.file.delete()
                status = EngineStatus(status.isModelLoaded, "녹음 실패: ${it.message ?: it.javaClass.simpleName}")
            }
            activeAudioRecording = null
            isRecordingAudio = false
        }
    }

    fun cancelRecording() {
        val recording = activeAudioRecording ?: return
        scope.launch {
            runCatching {
                recording.recorder.stop()
                recording.job.join()
                recording.recorder.release()
            }
            recording.file.delete()
            activeAudioRecording = null
            isRecordingAudio = false
            status = EngineStatus(status.isModelLoaded, "녹음을 취소했습니다.")
        }
    }

    fun startRecording() {
        runCatching {
            val recording = startAudioRecording(context, scope)
            activeAudioRecording = recording
            isRecordingAudio = true
            status = EngineStatus(status.isModelLoaded, "WAV 녹음 중입니다. 다시 누르면 첨부합니다.")
        }.onFailure {
            status = EngineStatus(status.isModelLoaded, "녹음 시작 실패: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    val recordAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) startRecording()
            else status = EngineStatus(status.isModelLoaded, "마이크 권한이 필요합니다.")
        },
    )

    fun resetToNewSession(sessionId: String = sessionStore.createSessionId()) {
        currentSessionId = sessionId
        messages.clear()
        messages += ChatMessage(ChatRole.Assistant, "새 상담을 시작했습니다. 지금 다루고 싶은 주제를 적어 주세요.")
        attachedAudioPath = null
        attachmentLabel = null
        input = ""
        status = EngineStatus(status.isModelLoaded, "새 상담 세션을 시작했습니다.")
        scope.launch {
            sessionStore.saveLastSession(messages.toList(), systemPrompt, importantMemories.toList(), currentSessionId)
            memoryStore.reindexSession(messages.toList(), currentSessionId)
            sessionSummaries = sessionStore.listSessions()
        }
    }

    fun loadSession(sessionId: String) {
        scope.launch {
            sessionStore.loadSession(sessionId)?.let { saved ->
                currentSessionId = saved.id
                messages.clear()
                messages.addAll(saved.messages.ifEmpty {
                    listOf(ChatMessage(ChatRole.Assistant, "불러온 상담에 메시지가 없습니다."))
                })
                if (saved.systemPrompt.isNotBlank()) {
                    systemPrompt = normalizeEditableSystemPrompt(saved.systemPrompt)
                }
                importantMemories.clear()
                importantMemories.addAll(saved.importantMemories)
                sessionStore.saveActiveSessionId(currentSessionId)
                memoryStore.reindexSession(messages.toList(), currentSessionId)
                sessionSummaries = sessionStore.listSessions()
                status = EngineStatus(status.isModelLoaded, "상담 세션을 불러왔습니다.")
            }
        }
    }

    val exportSession = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching {
                        exportChatSession(context, uri, messages.toList(), systemPrompt, importantMemories.toList())
                    }.onSuccess {
                        status = EngineStatus(status.isModelLoaded, "대화 파일을 내보냈습니다.")
                    }.onFailure {
                        status = EngineStatus(status.isModelLoaded, "대화 내보내기 실패: ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            }
        },
    )

    val importSession = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching {
                        importChatSession(context, uri)
                    }.onSuccess { imported ->
                        messages.clear()
                        messages.addAll(imported.messages.ifEmpty {
                            listOf(ChatMessage(ChatRole.Assistant, "불러온 대화에 메시지가 없습니다."))
                        })
                        systemPrompt = normalizeEditableSystemPrompt(imported.systemPrompt.ifBlank { systemPrompt })
                        importantMemories.clear()
                        importantMemories.addAll(imported.importantMemories)
                        currentSessionId = sessionStore.createSessionId()
                        sessionStore.saveLastSession(messages.toList(), systemPrompt, importantMemories.toList(), currentSessionId)
                        memoryStore.reindexSession(messages.toList(), currentSessionId)
                        sessionSummaries = sessionStore.listSessions()
                        status = EngineStatus(status.isModelLoaded, "대화 파일을 불러왔습니다.")
                    }.onFailure {
                        status = EngineStatus(status.isModelLoaded, "대화 불러오기 실패: ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            }
        },
    )

    if (showPromptOverview) {
        PromptOverviewDialog(
            systemPrompt = systemPrompt,
            preview = promptPreview,
            onApply = { updatedPrompt ->
                systemPrompt = normalizeEditableSystemPrompt(updatedPrompt)
                showPromptOverview = false
                scope.launch {
                    status = liteRtEngine.updateSystemInstruction(
                        buildSystemPromptWithMemories(systemPrompt, importantMemories),
                    )
                    sessionStore.saveLastSession(messages.toList(), systemPrompt, importantMemories.toList(), currentSessionId)
                    memoryStore.reindexSession(messages.toList(), currentSessionId)
                    sessionSummaries = sessionStore.listSessions()
                }
            },
            onDismiss = { showPromptOverview = false },
        )
    }

    if (showSystemPrompt) {
        AlertDialog(
            onDismissRequest = { showSystemPrompt = false },
            title = { Text("시스템 프롬프트") },
            text = {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    minLines = 10,
                    maxLines = 18,
                    label = { Text("말투 / 응답 선호") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSystemPrompt = false
                        systemPrompt = normalizeEditableSystemPrompt(systemPrompt)
                        scope.launch {
                            status = liteRtEngine.updateSystemInstruction(
                                buildSystemPromptWithMemories(systemPrompt, importantMemories),
                            )
                            sessionStore.saveLastSession(messages.toList(), systemPrompt, importantMemories.toList(), currentSessionId)
                            memoryStore.reindexSession(messages.toList(), currentSessionId)
                            sessionSummaries = sessionStore.listSessions()
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("적용")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showSystemPrompt = false },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("닫기")
                }
            },
        )
    }

    if (showMemories) {
        MemoryDialog(
            defaultMemories = defaultImportantMemories,
            userMemories = importantMemories,
            onDeleteUserMemory = { memory ->
                importantMemories.remove(memory)
                scope.launch {
                    sessionStore.saveLastSession(messages.toList(), systemPrompt, importantMemories.toList(), currentSessionId)
                    sessionSummaries = sessionStore.listSessions()
                    status = if (status.isModelLoaded) {
                        liteRtEngine.updateSystemInstruction(
                            buildSystemPromptWithMemories(systemPrompt, importantMemories),
                        )
                    } else {
                        EngineStatus(status.isModelLoaded, "중요 기억을 삭제했습니다.")
                    }
                }
            },
            onDismiss = { showMemories = false },
        )
    }
    if (showChatSettings) {
        ChatSettingsDialog(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            status = status,
            includeHealthContext = includeHealthContext,
            onToggleHealthContext = { includeHealthContext = !includeHealthContext },
            includePhenotypeContext = includePhenotypeContext,
            onTogglePhenotypeContext = { includePhenotypeContext = !includePhenotypeContext },
            includeGalleryAnalysisContext = includeGalleryAnalysisContext,
            onToggleGalleryAnalysisContext = { includeGalleryAnalysisContext = !includeGalleryAnalysisContext },
            healthContextPeriod = healthContextPeriod,
            onCycleHealthPeriod = { healthContextPeriod = healthContextPeriod.next() },
            thinkingMode = thinkingMode,
            onCycleThinkingMode = { thinkingMode = thinkingMode.next() },
            directAttachmentMode = directAttachmentMode,
            onToggleDirectAttachmentMode = { directAttachmentMode = !directAttachmentMode },
            chatFontSize = chatFontSize,
            onChatFontSizeChange = { chatFontSize = it },
            onLoadModel = { modelPicker.launch(arrayOf("*/*")) },
            onShowSystemPrompt = {
                showPromptOverview = true
                loadPromptPreview()
            },
            onShowMemories = { showMemories = true },
            onNewSession = {
                showChatSettings = false
                resetToNewSession()
            },
            onShowSessions = {
                showChatSettings = false
                scope.launch {
                    sessionSummaries = sessionStore.listSessions()
                    showSessionList = true
                }
            },
            onExportSession = { exportSession.launch("counseling_session.json") },
            onImportSession = { importSession.launch(arrayOf("application/json", "text/*", "*/*")) },
            onDismiss = { showChatSettings = false },
        )
    }
    if (showSessionList) {
        SessionListDialog(
            sessions = sessionSummaries,
            currentSessionId = currentSessionId,
            onSelectSession = { sessionId ->
                showSessionList = false
                loadSession(sessionId)
            },
            onNewSession = {
                showSessionList = false
                resetToNewSession()
            },
            onDismiss = { showSessionList = false },
        )
    }

    LaunchedEffect(settingsOpenRequests) {
        if (settingsOpenRequests > 0) {
            showChatSettings = true
        }
    }

    LaunchedEffect(Unit) {
        sessionStore.loadLastSession()?.let { saved ->
            if (saved.messages.isNotEmpty()) {
                messages.clear()
                messages.addAll(saved.messages)
            }
            if (saved.systemPrompt.isNotBlank()) {
                systemPrompt = normalizeEditableSystemPrompt(saved.systemPrompt)
            }
            importantMemories.clear()
            importantMemories.addAll(saved.importantMemories)
            currentSessionId = saved.id
            memoryStore.reindexSession(messages.toList(), currentSessionId)
            sessionSummaries = sessionStore.listSessions()
        }
        status = EngineStatus(false, "모델 파일을 선택해 주세요.")
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val scrollingDown = index > lastIndex || (index == lastIndex && offset > lastOffset)
                val scrollingUp = index < lastIndex || (index == lastIndex && offset < lastOffset - 8)
                when {
                    scrollingDown -> onChromeVisibleChange(false)
                    scrollingUp -> onChromeVisibleChange(true)
                }
                lastIndex = index
                lastOffset = offset
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                activeAudioRecording?.recorder?.stop()
                activeAudioRecording?.recorder?.release()
                voiceEmotionAnalyzer.close()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (false) {
        AnimatedVisibility(visible = chromeVisible) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Gemma 4 E4B IT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "LiteRT-LM 로컬 실행",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        OutlinedButton(
                            enabled = !isLoadingModel && !isGenerating,
                            onClick = { showChatMenu = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Text("☰")
                        }
                        DropdownMenu(
                            expanded = showChatMenu,
                            onDismissRequest = { showChatMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("모델 로드") },
                                onClick = {
                                    showChatMenu = false
                                    modelPicker.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("프롬프트") },
                                onClick = {
                                    showChatMenu = false
                                    showSystemPrompt = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("기억") },
                                onClick = {
                                    showChatMenu = false
                                    showMemories = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (includeHealthContext) "건강 데이터 제외" else "건강 데이터 포함") },
                                onClick = {
                                    includeHealthContext = !includeHealthContext
                                    showChatMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (includePhenotypeContext) "생활 패턴 제외" else "생활 패턴 포함") },
                                onClick = {
                                    includePhenotypeContext = !includePhenotypeContext
                                    showChatMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("건강 기간: ${healthContextPeriod.next().label}로 변경") },
                                onClick = {
                                    healthContextPeriod = healthContextPeriod.next()
                                    showChatMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("대화 내보내기") },
                                onClick = {
                                    showChatMenu = false
                                    exportSession.launch("counseling_session.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("대화 불러오기") },
                                onClick = {
                                    showChatMenu = false
                                    importSession.launch(arrayOf("application/json", "text/*", "*/*"))
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = if (status.isModelLoaded) "모델 준비됨" else "모델 필요",
                        active = status.isModelLoaded,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (includeHealthContext) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = if (includeHealthContext) "건강 ${healthContextPeriod.label}" else "건강 제외",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        }

        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = if (status.isModelLoaded) "모델 준비" else "모델 필요",
                    active = status.isModelLoaded,
                )
                Text(
                    text = buildList {
                        add(if (includeHealthContext) "건강 ${healthContextPeriod.label}" else "건강 제외")
                        add(if (includePhenotypeContext) "패턴 포함" else "패턴 제외")
                    }.joinToString(" · "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showChatSettings = true }) {
                    Text("설정")
                }
            }
        }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages) { message ->
                MessageBubble(message = message, fontSize = chatFontSize)
            }

            if (isGenerating || isLoadingModel) {
                item {
                    BusyStatusRow(
                        title = when {
                            isLoadingModel -> "모델 로딩 중"
                            isThinking -> "사고 중"
                            else -> "응답 생성 중"
                        },
                        message = status.message,
                    )
                }
            }
        }

        MessageInput2(
            value = input,
            enabled = status.isModelLoaded && !isGenerating && !isLoadingModel,
            attachmentLabel = attachmentLabel,
            onValueChange = { input = it },
            thinkingMode = thinkingMode,
            onCycleThinkingMode = { thinkingMode = thinkingMode.next() },
            onRecordAudio = {
                if (isRecordingAudio) {
                    stopRecordingAndAttach()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onSpeechInput = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "말씀해 주세요")
                }
                speechInput.launch(intent)
            },
            onClearAttachment = {
                if (isRecordingAudio) cancelRecording()
                attachedAudioPath = null
                attachmentLabel = null
            },
            onSend = {
                val text = input.trim()
                if ((text.isEmpty() && attachmentLabel == null) || isGenerating || isRecordingAudio || !status.isModelLoaded) return@MessageInput2

                input = ""
                val rawUserMessage = ChatMessage(
                    role = ChatRole.User,
                    content = text.ifBlank {
                        if (attachedAudioPath != null) {
                            "사용자의 음성 입력이 첨부되었습니다. 첨부된 오디오를 직접 듣고, 들은 내용에 근거해 상담 응답을 해 주세요. 음성이 불명확하면 어떤 부분이 불명확한지 말하고 확인 질문을 해 주세요."
                        } else {
                            "첨부 파일을 확인해 주세요."
                        }
                    },
                    audioPath = attachedAudioPath,
                    attachmentLabel = attachmentLabel,
                )
                attachedAudioPath = null
                attachmentLabel = null
                isGenerating = true
                val useThinking = shouldUseThinkingMode(
                    mode = thinkingMode,
                    text = text,
                    hasAttachment = rawUserMessage.imagePath != null || rawUserMessage.audioPath != null,
                    importantMemories = importantMemories,
                )
                isThinking = useThinking

                scope.launch {
                    val userMessage = rawUserMessage.withVoiceEmotionContext(
                        voiceEmotionAnalyzer = voiceEmotionAnalyzer,
                    ) { message ->
                        status = EngineStatus(status.isModelLoaded, message)
                    }
                    messages += userMessage
                    var savedImportantMemory = false
                    extractImportantMemory(rawUserMessage.content)?.let { memory ->
                        if (importantMemories.none { it.equals(memory, ignoreCase = true) }) {
                            importantMemories += memory
                            savedImportantMemory = true
                        }
                    }
                    if (savedImportantMemory) {
                        status = EngineStatus(status.isModelLoaded, "중요한 내용으로 저장했습니다.")
                    }
                    messages += ChatMessage(ChatRole.Assistant, "")
                    val assistantIndex = messages.lastIndex
                    sessionStore.saveLastSession(messages.toList(), systemPrompt, importantMemories.toList(), currentSessionId)
                    memoryStore.reindexSession(messages.toList(), currentSessionId)
                    sessionSummaries = sessionStore.listSessions()
                    val healthContext = if (includeHealthContext) {
                        status = EngineStatus(status.isModelLoaded, "Health Connect 요약을 읽고 있습니다...")
                        refreshHealthRagSlot(context, healthContextPeriod).also {
                            if (it == null) status = EngineStatus(status.isModelLoaded, "Health Connect 요약을 RAG 슬롯에 갱신하지 못했습니다.")
                        }
                    } else {
                        null
                    }
                    val phenotypeContext = if (includePhenotypeContext) {
                        status = EngineStatus(status.isModelLoaded, "생활 패턴 요약을 읽고 있습니다...")
                        val result = refreshPhenotypeRagSlot(context)
                        if (result.contextText == null) {
                            status = EngineStatus(status.isModelLoaded, result.message)
                        }
                        result.contextText
                    } else {
                        null
                    }
                    val galleryAnalysisContext = if (includeGalleryAnalysisContext) {
                        status = EngineStatus(status.isModelLoaded, "Gallery 분석 맥락을 읽고 있습니다...")
                        val result = readGalleryAnalysisPromptContext(context)
                        if (result.contextText == null) {
                            status = EngineStatus(status.isModelLoaded, result.message)
                        }
                        result.contextText
                    } else {
                        null
                    }
                    val relevantMemories = memoryStore
                        .searchRelevant(text, sessionId = currentSessionId)
                        .filterNot { it.role == ChatRole.User && it.content == userMessage.content }
                    val promptMessages = messages
                        .take(assistantIndex)
                        .withMemoryContext(importantMemories, relevantMemories)
                        .withHealthContext(healthContext)
                        .withPhenotypeContext(phenotypeContext)
                        .withGalleryAnalysisContext(galleryAnalysisContext)
                        .withThinkingInstruction(useThinking)
                        .toList()
                    liteRtEngine.setDirectAttachmentMode(directAttachmentMode)
                    val reply = liteRtEngine.generateStreaming(promptMessages) { partial ->
                        messages[assistantIndex] = ChatMessage(ChatRole.Assistant, stripInternalThinking(partial))
                    }
                    messages[assistantIndex] = ChatMessage(
                        ChatRole.Assistant,
                        stripInternalThinking(reply).ifBlank { "응답이 비어 있습니다." },
                    )
                    sessionStore.saveLastSession(messages.toList(), systemPrompt, importantMemories.toList(), currentSessionId)
                    memoryStore.reindexSession(messages.toList(), currentSessionId)
                    sessionSummaries = sessionStore.listSessions()
                    isGenerating = false
                    isThinking = false
                }
            },
            isRecordingAudio = isRecordingAudio,
            isThinking = isThinking,
            imeBottomPadding = imeBottomPadding,
            fontSize = chatFontSize,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        )
    }
}

private suspend fun ChatMessage.withVoiceEmotionContext(
    voiceEmotionAnalyzer: LiteRtVoiceEmotionAnalyzer,
    updateStatus: (String) -> Unit,
): ChatMessage {
    val audio = audioPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 44L }
        ?: return this
    updateStatus("음성 감정 분석 중입니다...")
    val result = runCatching {
        voiceEmotionAnalyzer.analyze(audio)
    }.getOrNull()
    if (result == null) {
        updateStatus("음성 감정 모델을 찾지 못했거나 분석에 실패했습니다. 음성 첨부만 전달합니다.")
        return this
    }
    val contextText = voiceEmotionAnalyzer.buildPromptContext(result)
    updateStatus("음성 감정 분석 완료: ${result.displaySummary()} · ${result.probabilitySummary()}")
    val visibleLabel = listOfNotNull(
        attachmentLabel,
        result.displaySummary(),
    ).joinToString(" · ")
    return copy(
        attachmentLabel = visibleLabel,
        content = """
            $contextText

            [사용자 입력]
            ${content.trim()}
        """.trimIndent(),
    )
}

