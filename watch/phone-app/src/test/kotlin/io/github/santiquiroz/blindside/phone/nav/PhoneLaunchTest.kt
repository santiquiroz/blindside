package io.github.santiquiroz.blindside.phone.nav

import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_ACTIVITY_RECOGNITION
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneLaunchTest {
    private val paired = AppSettings(beltAddress = "AA:BB:CC:DD:EE:FF")

    @Test
    fun `a paired dual-link belt with bluetooth granted starts the radar on open`() {
        assertEquals(PhoneLaunch.AUTO_START, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `the first pairing waits for the user`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(AppSettings(), sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `a missing bluetooth grant waits for a tap so the prompt is seen`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = false, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `turning auto start off always waits`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired.copy(autoStartRadar = false), sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `a session already running is never started twice`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = true, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `a belt that holds one link never auto-starts, so the phone cannot take it from the watch`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.SINGLE_LINK))
    }

    @Test
    fun `a firmware the phone has not seen yet waits for a tap`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.UNKNOWN))
    }

    @Test
    fun `the phone asks for bluetooth and notifications, never for physical activity`() {
        assertTrue(PERMISSION_BLUETOOTH_SCAN in PHONE_PERMISSIONS && PERMISSION_BLUETOOTH_CONNECT in PHONE_PERMISSIONS)
        assertFalse(PERMISSION_ACTIVITY_RECOGNITION in PHONE_PERMISSIONS)
    }
}
