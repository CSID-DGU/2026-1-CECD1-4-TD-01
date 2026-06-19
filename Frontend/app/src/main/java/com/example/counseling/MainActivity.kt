package com.example.counseling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
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
            TopAppBar(
                title = {
                    Column {
                        Text(if (presentationMode) "On-mom" else "On-mom Dev", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (presentationMode) "온마음 마음상담" else "개발자/디버그 화면",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    Box {
                        TextButton(
                            onClick = {
                                if (screen == AppScreen.Chat) {
                                    chatChromeVisible = true
                                    settingsOpenRequests += 1
                                } else {
                                    showThemeMenu = true
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text(if (screen == AppScreen.Chat) "설정" else themeMode.label)
                        }
                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false },
                        ) {
                            AppThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        showThemeMenu = false
                                        onThemeModeChange(mode)
                                    },
                                )
                            }
                        }
                    }
                },
            )
            }
        },
        bottomBar = {
            if (!imeVisible) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                ) {
                    AppScreen.entries.filterNot { it == AppScreen.Settings }.forEach { item ->
                        NavigationBarItem(
                            selected = screen == item,
                            onClick = { screen = item },
                            icon = { Text(item.icon) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
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
