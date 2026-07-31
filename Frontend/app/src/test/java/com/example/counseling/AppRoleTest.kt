package com.example.counseling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoleTest {
    @Test
    fun guardianGetsAlertsAndSharedIot() {
        val screens = screensFor(AppRole.Guardian, developerMode = false)
        assertEquals(listOf(AppScreen.Guardian, AppScreen.Iot), screens)
    }

    @Test
    fun userKeepsExistingScreensAndGetsIot() {
        val screens = screensFor(AppRole.User, developerMode = false)
        assertTrue(screens.containsAll(listOf(AppScreen.Chat, AppScreen.Gallery, AppScreen.Health, AppScreen.Phenotype)))
        assertTrue(screens.contains(AppScreen.Iot))
        assertFalse(screens.contains(AppScreen.Guardian))
    }

    @Test
    fun developerScreensDoNotDependOnSelectedRole() {
        assertEquals(
            screensFor(AppRole.User, developerMode = true),
            screensFor(AppRole.Guardian, developerMode = true),
        )
    }
}
