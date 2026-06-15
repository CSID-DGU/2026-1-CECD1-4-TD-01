package com.example.counseling.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.counseling.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    secondary = Teal80,
    tertiary = Coral80,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = Color(0xFF2C3633),
    primaryContainer = Color(0xFF21484E),
    secondaryContainer = Color(0xFF2D4938),
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    secondary = Teal40,
    tertiary = Coral40,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = Color(0xFFE7EEEB),
    primaryContainer = Color(0xFFD5ECEF),
    secondaryContainer = Color(0xFFDDEFE3),
)

@Composable
fun CounselingTheme(
    themeMode: AppThemeMode = AppThemeMode.Light,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
        AppThemeMode.System -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
