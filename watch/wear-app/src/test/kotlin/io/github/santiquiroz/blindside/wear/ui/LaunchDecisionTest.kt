package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.wear.permissions.PERMISSION_ACTIVITY_RECOGNITION
import io.github.santiquiroz.blindside.wear.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.wear.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaunchDecisionTest {
    private val paired = AppSettings(beltAddress = "AA:BB:CC:DD:EE:FF")
    private val bluetoothGrants = mapOf(PERMISSION_BLUETOOTH_SCAN to true, PERMISSION_BLUETOOTH_CONNECT to true)

    @Test
    fun `auto start is on by default`() {
        assertTrue(AppSettings().autoStartRadar)
    }

    @Test
    fun `a paired belt with bluetooth granted starts the radar on open`() {
        assertTrue(shouldAutoStart(paired, permissionsGranted = true))
        assertEquals(LaunchAction.START_RADAR, launchAction(paired, sessionRunning = false, permissionsGranted = true))
    }

    @Test
    fun `without a paired belt the home waits for the first pairing`() {
        assertFalse(shouldAutoStart(AppSettings(), permissionsGranted = true))
        assertEquals(LaunchAction.SHOW_HOME, launchAction(AppSettings(), sessionRunning = false, permissionsGranted = true))
    }

    @Test
    fun `missing bluetooth permission keeps the home so the prompt is shown on tap`() {
        assertFalse(shouldAutoStart(paired, permissionsGranted = false))
        assertEquals(LaunchAction.SHOW_HOME, launchAction(paired, sessionRunning = false, permissionsGranted = false))
    }

    @Test
    fun `turning auto start off always opens the home`() {
        val manual = paired.copy(autoStartRadar = false)
        assertFalse(shouldAutoStart(manual, permissionsGranted = true))
        assertEquals(LaunchAction.SHOW_HOME, launchAction(manual, sessionRunning = false, permissionsGranted = true))
        assertEquals(LaunchAction.SHOW_HOME, launchAction(manual, sessionRunning = true, permissionsGranted = true))
    }

    @Test
    fun `a game already running reopens straight on the radar without starting another`() {
        assertEquals(LaunchAction.SHOW_RADAR, launchAction(paired, sessionRunning = true, permissionsGranted = true))
        assertEquals(LaunchAction.SHOW_RADAR, launchAction(AppSettings(), sessionRunning = true, permissionsGranted = false))
    }

    @Test
    fun `tapping start goes straight to the radar once bluetooth is granted`() {
        assertEquals(StartTapAction.START_SESSION, startTapAction(bluetoothGrants + (PERMISSION_ACTIVITY_RECOGNITION to false)))
    }

    @Test
    fun `tapping start asks for permissions only while bluetooth is missing`() {
        assertEquals(StartTapAction.REQUEST_PERMISSIONS, startTapAction(emptyMap()))
        assertEquals(StartTapAction.REQUEST_PERMISSIONS, startTapAction(bluetoothGrants + (PERMISSION_BLUETOOTH_CONNECT to false)))
    }
}
