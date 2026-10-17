package com.karoocameracontrol.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNavigationTest {
    @Test
    fun automationChildrenReturnThroughTheirParents() {
        for (child in listOf(ScreenDestination.RADAR, ScreenDestination.DESCENT)) {
            var navigation =
                ScreenNavigation().open(ScreenDestination.SETTINGS)
                    .open(ScreenDestination.AUTOMATIC_RECORDING).open(child)
            navigation = navigation.back()
            assertEquals(ScreenDestination.AUTOMATIC_RECORDING, navigation.destination)
            navigation = navigation.back()
            assertEquals(ScreenDestination.SETTINGS, navigation.destination)
            assertEquals(ScreenDestination.MAIN, navigation.back().destination)
        }
    }

    @Test
    fun settingsChildrenReturnToHubThenToScanningOrigin() {
        for (child in listOf(ScreenDestination.ALERTS, ScreenDestination.CAMERA_CONNECTION, ScreenDestination.FEEDBACK)) {
            val navigation =
                ScreenNavigation().open(ScreenDestination.SCANNING)
                    .open(ScreenDestination.SETTINGS).open(child)
            assertEquals(ScreenDestination.SETTINGS, navigation.back().destination)
            assertEquals(ScreenDestination.SCANNING, navigation.back().back().destination)
        }
    }

    @Test
    fun connectionCompletingWhileInSettingsDoesNotDismissSettingsOrRestoreScanning() {
        val navigation =
            ScreenNavigation().open(ScreenDestination.SCANNING)
                .open(ScreenDestination.SETTINGS).open(ScreenDestination.RADAR).cameraConnected()
        assertEquals(ScreenDestination.RADAR, navigation.destination)
        assertEquals(ScreenDestination.MAIN, navigation.back().back().back().destination)
        assertEquals(ScreenDestination.MAIN, ScreenNavigation(ScreenDestination.SCANNING).cameraConnected().destination)
    }

    @Test
    fun repeatedSettingsTapDoesNotOverwriteReturnDestination() {
        val navigation = ScreenNavigation().open(ScreenDestination.SCANNING).open(ScreenDestination.SETTINGS)
        assertEquals(navigation, navigation.open(ScreenDestination.SETTINGS))
        assertEquals(ScreenDestination.SCANNING, navigation.open(ScreenDestination.SETTINGS).back().destination)
    }

    @Test
    fun presetsAndScanningReturnToMainWithoutShowingSettingsInsidePresets() {
        assertEquals(ScreenDestination.MAIN, ScreenNavigation(ScreenDestination.PRESETS).back().destination)
        assertEquals(ScreenDestination.MAIN, ScreenNavigation(ScreenDestination.SCANNING).back().destination)
        assertFalse(ScreenNavigation(ScreenDestination.PRESETS).showsSettingsAction)
        assertTrue(ScreenNavigation(ScreenDestination.SCANNING).showsSettingsAction)
    }
}
