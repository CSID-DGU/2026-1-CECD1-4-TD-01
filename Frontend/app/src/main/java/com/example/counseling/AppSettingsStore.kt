package com.example.counseling

import android.content.Context

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun loadThemeMode(): AppThemeMode {
        val saved = preferences.getString(KEY_THEME_MODE, AppThemeMode.Light.name)
        return runCatching { AppThemeMode.valueOf(saved ?: AppThemeMode.Light.name) }
            .getOrDefault(AppThemeMode.Light)
    }

    fun saveThemeMode(mode: AppThemeMode) {
        preferences.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
    }
}
