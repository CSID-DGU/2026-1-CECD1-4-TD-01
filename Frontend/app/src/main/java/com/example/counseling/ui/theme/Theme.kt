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

private fun lightOnMomColorScheme(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    primaryContainer: Color,
    secondaryContainer: Color,
    tertiaryContainer: Color,
    surfaceVariant: Color,
) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    secondary = secondary,
    tertiary = tertiary,
    background = LightBackground,
    onBackground = Ink,
    surface = LightSurface,
    onSurface = Ink,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = MutedInk,
    primaryContainer = primaryContainer,
    onPrimaryContainer = Ink,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = Ink,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = Ink,
    outline = Color(0xFF8A9699),
    outlineVariant = Color(0xFFD5DDDF),
)

private val LightColorScheme = lightOnMomColorScheme(
    primary = Color(0xFF176C72),
    secondary = Color(0xFF54715D),
    tertiary = Color(0xFF9D5A44),
    primaryContainer = Color(0xFFD9F0F1),
    secondaryContainer = Color(0xFFE1EEDF),
    tertiaryContainer = Color(0xFFFFDFD4),
    surfaceVariant = Color(0xFFEEF3F2),
)

private val SageColorScheme = lightOnMomColorScheme(
    primary = Color(0xFF3E6B52),
    secondary = Color(0xFF6E6542),
    tertiary = Color(0xFF8E5D64),
    primaryContainer = Color(0xFFDCEBDD),
    secondaryContainer = Color(0xFFEDE6C8),
    tertiaryContainer = Color(0xFFF6D9DE),
    surfaceVariant = Color(0xFFF0F4ED),
)

private val SkyColorScheme = lightOnMomColorScheme(
    primary = Color(0xFF2F6690),
    secondary = Color(0xFF5B6679),
    tertiary = Color(0xFF7D5C8A),
    primaryContainer = Color(0xFFDCEBFA),
    secondaryContainer = Color(0xFFE4E9F2),
    tertiaryContainer = Color(0xFFF0DDF5),
    surfaceVariant = Color(0xFFF0F5FA),
)

private val RoseColorScheme = lightOnMomColorScheme(
    primary = Color(0xFF9A4D5D),
    secondary = Color(0xFF6F6752),
    tertiary = Color(0xFF6A6E9D),
    primaryContainer = Color(0xFFF8DDE3),
    secondaryContainer = Color(0xFFEDE7D7),
    tertiaryContainer = Color(0xFFE5E6FA),
    surfaceVariant = Color(0xFFF8F0F2),
)

private val MonoColorScheme = lightOnMomColorScheme(
    primary = Color(0xFF30363A),
    secondary = Color(0xFF596166),
    tertiary = Color(0xFF746153),
    primaryContainer = Color(0xFFE2E5E7),
    secondaryContainer = Color(0xFFEDEFF0),
    tertiaryContainer = Color(0xFFEDE4DD),
    surfaceVariant = Color(0xFFF2F3F4),
)

@Composable
fun CounselingTheme(
    themeMode: AppThemeMode = AppThemeMode.Light,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.Light,
        AppThemeMode.Sage,
        AppThemeMode.Sky,
        AppThemeMode.Rose,
        AppThemeMode.Mono -> false
        AppThemeMode.Dark -> true
        AppThemeMode.System -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        themeMode == AppThemeMode.Sage -> SageColorScheme
        themeMode == AppThemeMode.Sky -> SkyColorScheme
        themeMode == AppThemeMode.Rose -> RoseColorScheme
        themeMode == AppThemeMode.Mono -> MonoColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
