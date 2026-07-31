package com.example.counseling

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.counseling.llm.ChatMessage
import com.example.counseling.llm.ChatRole
import com.example.counseling.ui.theme.CounselingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialScreen = if (intent?.action?.contains("health", ignoreCase = true) == true) {
            AppScreen.Health
        } else {
            AppScreen.Chat
        }
        val settingsStore = AppSettingsStore(applicationContext)
        setContent {
            var themeMode by remember { mutableStateOf(settingsStore.loadThemeMode()) }
            var role by remember { mutableStateOf(settingsStore.loadRole()) }
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {}

            LaunchedEffect(role) {
                if (role == AppRole.Guardian) {
                    if (
                        Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    GuardianAlertMonitorService.start(this@MainActivity)
                } else {
                    GuardianAlertMonitorService.stop(this@MainActivity)
                }
            }

            CounselingTheme(themeMode = themeMode) {
                val selectedRole = role
                if (selectedRole == null) {
                    RoleSelectionScreen { selected ->
                        settingsStore.saveRole(selected)
                        role = selected
                    }
                } else {
                    CounselingApp(
                        role = selectedRole,
                        initialScreen = initialScreen,
                        themeMode = themeMode,
                        onRoleChange = { selected ->
                            settingsStore.saveRole(selected)
                            role = selected
                        },
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            settingsStore.saveThemeMode(mode)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CounselingApp(
    role: AppRole,
    initialScreen: AppScreen = AppScreen.Chat,
    themeMode: AppThemeMode,
    onRoleChange: (AppRole) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    var developerMode by remember { mutableStateOf(false) }
    var screen by remember(role) {
        mutableStateOf(
            if (role == AppRole.Guardian) AppScreen.Guardian else initialScreen,
        )
    }
    var chatChromeVisible by remember { mutableStateOf(true) }
    var settingsOpenRequests by remember { mutableStateOf(0) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showJetsonSync by remember { mutableStateOf(false) }
    val presentationMode = !developerMode
    val visibleScreens = screensFor(role, developerMode)
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(screen, role, developerMode) {
        if (screen !in visibleScreens) {
            screen = initialScreenFor(role, developerMode)
        }
        if (screen != AppScreen.Chat) chatChromeVisible = true
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(visible = screen != AppScreen.Chat || (chatChromeVisible && !imeVisible)) {
                OnMomTopChrome(
                    role = role,
                    developerMode = developerMode,
                    screen = screen,
                    onSettings = { showAppSettings = true },
                )
            }
        },
        bottomBar = {
            if (!imeVisible) {
                OnMomBottomTabs(
                    selected = screen,
                    screens = visibleScreens,
                    onSelect = { screen = it },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (role == AppRole.User || developerMode) {
                ChatScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    presentationMode = presentationMode,
                    onPresentationModeChange = { developerMode = !it },
                    chromeVisible = chatChromeVisible,
                    onChromeVisibleChange = { chatChromeVisible = it },
                    settingsOpenRequests = settingsOpenRequests,
                    imeVisible = imeVisible,
                )
            }
            when (screen) {
                AppScreen.Chat, AppScreen.Settings -> Unit
                AppScreen.Guardian -> ScreenOverlay {
                    GuardianScreen(onConfigureJetson = { showJetsonSync = true })
                }
                AppScreen.Gallery -> ScreenOverlay { GalleryScreen(presentationMode = presentationMode) }
                AppScreen.Health -> ScreenOverlay { HealthScreen() }
                AppScreen.Phenotype -> ScreenOverlay { PhenotypeScreen(presentationMode = presentationMode) }
                AppScreen.Iot -> ScreenOverlay {
                    IotControlScreen(onConfigureJetson = { showJetsonSync = true })
                }
            }
        }
    }

    if (showAppSettings) {
        AppModeSettingsDialog(
            role = role,
            developerMode = developerMode,
            themeMode = themeMode,
            onRoleChange = {
                showAppSettings = false
                onRoleChange(it)
            },
            onDeveloperModeChange = {
                developerMode = it
                screen = initialScreenFor(role, it)
                showAppSettings = false
            },
            onThemeModeChange = onThemeModeChange,
            onOpenJetson = {
                showAppSettings = false
                showJetsonSync = true
            },
            onDismiss = { showAppSettings = false },
        )
    }
    if (showJetsonSync) {
        JetsonSyncDialog(
            developerMode = developerMode,
            connectionOnly = role == AppRole.Guardian && !developerMode,
            onDismiss = { showJetsonSync = false },
        )
    }
}

@Composable
private fun OnMomTopChrome(
    role: AppRole,
    developerMode: Boolean,
    screen: AppScreen,
    onSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .shadow(14.dp, RoundedCornerShape(28.dp), clip = false)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("온", fontWeight = FontWeight.Bold)
                    }
                }
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        when {
                            developerMode -> "On-mom Dev"
                            role == AppRole.Guardian -> "On-mom Guardian"
                            else -> "On-mom"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            developerMode -> "개발자 관찰 화면"
                            role == AppRole.Guardian -> "위험 알림과 생활 환경"
                            screen == AppScreen.Iot -> "우리 집 IoT"
                            else -> "온마음 마음상담"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onSettings),
                    ) {
                        Text(
                            text = "설정",
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnMomBottomTabs(
    selected: AppScreen,
    screens: List<AppScreen>,
    onSelect: (AppScreen) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .shadow(18.dp, RoundedCornerShape(30.dp), clip = false)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f), RoundedCornerShape(30.dp)),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                screens.forEach { item ->
                    val isSelected = selected == item
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(item) },
                        shape = RoundedCornerShape(24.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            Text(item.icon, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenOverlay(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
fun CounselingAppPreview() {
    CounselingTheme {
        MessageBubble(
            ChatMessage(
                role = ChatRole.Assistant,
                content = "모델 로드가 끝났습니다. 메시지를 입력해 테스트하세요.",
            ),
        )
    }
}
