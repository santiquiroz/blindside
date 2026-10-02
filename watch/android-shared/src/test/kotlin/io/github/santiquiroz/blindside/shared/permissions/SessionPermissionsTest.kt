package io.github.santiquiroz.blindside.shared.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionPermissionsTest {
    private val allGranted = mapOf(
        PERMISSION_BLUETOOTH_SCAN to true,
        PERMISSION_BLUETOOTH_CONNECT to true,
        PERMISSION_ACTIVITY_RECOGNITION to true,
        PERMISSION_POST_NOTIFICATIONS to true,
    )

    @Test
    fun `starts with watch steps when everything is granted`() {
        assertEquals(StartDecision.Start(watchSteps = true), startDecision(allGranted))
    }

    @Test
    fun `starts without watch steps when activity recognition is denied`() {
        val grants = allGranted + (PERMISSION_ACTIVITY_RECOGNITION to false)
        assertEquals(StartDecision.Start(watchSteps = false), startDecision(grants))
    }

    @Test
    fun `starts even when notifications are denied`() {
        val grants = allGranted + (PERMISSION_POST_NOTIFICATIONS to false)
        assertEquals(StartDecision.Start(watchSteps = true), startDecision(grants))
    }

    @Test
    fun `starts even when location is denied`() {
        val grants = allGranted + (PERMISSION_ACCESS_FINE_LOCATION to false)
        assertEquals(StartDecision.Start(watchSteps = true), startDecision(grants))
    }

    @Test
    fun `blocks when bluetooth connect is denied`() {
        val grants = allGranted + (PERMISSION_BLUETOOTH_CONNECT to false)
        assertEquals(StartDecision.BlockedBluetoothDenied, startDecision(grants))
    }

    @Test
    fun `blocks when bluetooth scan is missing from the result`() {
        assertEquals(StartDecision.BlockedBluetoothDenied, startDecision(allGranted - PERMISSION_BLUETOOTH_SCAN))
    }

    @Test
    fun `starting skips the prompt once bluetooth is granted even if optional permissions were denied`() {
        val grants = allGranted + (PERMISSION_ACTIVITY_RECOGNITION to false) + (PERMISSION_POST_NOTIFICATIONS to false)
        assertFalse(shouldRequestPermissions(grants))
    }

    @Test
    fun `starting prompts while any bluetooth permission is missing`() {
        assertTrue(shouldRequestPermissions(emptyMap()))
        assertTrue(shouldRequestPermissions(allGranted + (PERMISSION_BLUETOOTH_SCAN to false)))
    }

    @Test
    fun `requests every permission the session can use`() {
        val expected = setOf(
            PERMISSION_BLUETOOTH_SCAN,
            PERMISSION_BLUETOOTH_CONNECT,
            PERMISSION_ACTIVITY_RECOGNITION,
            PERMISSION_POST_NOTIFICATIONS,
            PERMISSION_ACCESS_FINE_LOCATION,
        )
        assertEquals(expected, SESSION_PERMISSIONS.toSet())
    }
}
