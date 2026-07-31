package com.example.counseling

enum class AppRole(val label: String) {
    User("사용자"),
    Guardian("보호자"),
}

internal fun screensFor(role: AppRole, developerMode: Boolean): List<AppScreen> =
    if (developerMode) {
        listOf(
            AppScreen.Chat,
            AppScreen.Gallery,
            AppScreen.Health,
            AppScreen.Phenotype,
            AppScreen.Iot,
        )
    } else {
        when (role) {
            AppRole.User -> listOf(
                AppScreen.Chat,
                AppScreen.Gallery,
                AppScreen.Health,
                AppScreen.Phenotype,
                AppScreen.Iot,
            )
            AppRole.Guardian -> listOf(AppScreen.Guardian, AppScreen.Iot)
        }
    }

internal fun initialScreenFor(role: AppRole, developerMode: Boolean): AppScreen =
    screensFor(role, developerMode).first()
