package com.example.counseling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
            CounselingTheme(themeMode = themeMode) {
                CounselingApp(
                    initialScreen = initialScreen,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        settingsStore.saveThemeMode(mode)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CounselingApp(
    initialScreen: AppScreen = AppScreen.Chat,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    var screen by remember { mutableStateOf(initialScreen) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var chatChromeVisible by remember { mutableStateOf(true) }
    var settingsOpenRequests by remember { mutableStateOf(0) }
    var presentationMode by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(screen) {
        if (screen != AppScreen.Chat) chatChromeVisible = true
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(visible = screen != AppScreen.Chat || (chatChromeVisible && !imeVisible)) {
                OnMomTopChrome(
                    presentationMode = presentationMode,
                    screen = screen,
                    themeMode = themeMode,
                    showThemeMenu = showThemeMenu,
                    onToggleThemeMenu = { showThemeMenu = it },
                    onChatSettings = {
                        chatChromeVisible = true
                        settingsOpenRequests += 1
                    },
                    onThemeModeChange = onThemeModeChange,
                )
            }
        },
        bottomBar = {
            if (!imeVisible) {
                OnMomBottomTabs(
                    selected = screen,
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
            ChatScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                presentationMode = presentationMode,
                onPresentationModeChange = { presentationMode = it },
                chromeVisible = chatChromeVisible,
                onChromeVisibleChange = { chatChromeVisible = it },
                settingsOpenRequests = settingsOpenRequests,
                imeVisible = imeVisible,
            )
            when (screen) {
                AppScreen.Chat, AppScreen.Settings -> Unit
                AppScreen.Gallery -> ScreenOverlay { GalleryScreen(presentationMode = presentationMode) }
                AppScreen.Health -> ScreenOverlay { HealthScreen() }
                AppScreen.Phenotype -> ScreenOverlay { PhenotypeScreen(presentationMode = presentationMode) }
            }
        }
    }
}

@Composable
private fun OnMomTopChrome(
    presentationMode: Boolean,
    screen: AppScreen,
    themeMode: AppThemeMode,
    showThemeMenu: Boolean,
    onToggleThemeMenu: (Boolean) -> Unit,
    onChatSettings: () -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
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
                        if (presentationMode) "On-mom" else "On-mom Dev",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (presentationMode) "온마음 마음상담" else "개발자 관찰 화면",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            if (screen == AppScreen.Chat) onChatSettings() else onToggleThemeMenu(true)
                        },
                    ) {
                        Text(
                            text = if (screen == AppScreen.Chat) "설정" else themeMode.label,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    DropdownMenu(
                        expanded = showThemeMenu,
                        onDismissRequest = { onToggleThemeMenu(false) },
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    onToggleThemeMenu(false)
                                    onThemeModeChange(mode)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnMomBottomTabs(
    selected: AppScreen,
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
                AppScreen.entries.filterNot { it == AppScreen.Settings }.forEach { item ->
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
