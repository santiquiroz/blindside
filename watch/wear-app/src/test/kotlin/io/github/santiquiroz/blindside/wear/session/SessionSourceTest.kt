package io.github.santiquiroz.blindside.wear.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionSourceTest {
    @Test
    fun `belt sessions run as connected device plus health`() {
        assertEquals(0x10 or 0x100, foregroundTypesFor(SessionSource.BELT))
    }

    @Test
    fun `demo sessions need no bluetooth service type`() {
        assertEquals(0x100, foregroundTypesFor(SessionSource.DEMO))
    }

    @Test
    fun `a belt session without bluetooth permission is blocked`() {
        assertEquals(StartError.BLUETOOTH_PERMISSION_MISSING, startBlocker(SessionSource.BELT, bluetoothGranted = false, hasAdapter = true))
    }

    @Test
    fun `a watch without a bluetooth adapter cannot run a belt session`() {
        assertEquals(StartError.BLUETOOTH_UNAVAILABLE, startBlocker(SessionSource.BELT, bluetoothGranted = true, hasAdapter = false))
    }

    @Test
    fun `the demo always starts`() {
        assertNull(startBlocker(SessionSource.DEMO, bluetoothGranted = false, hasAdapter = false))
        assertNull(startBlocker(SessionSource.BELT, bluetoothGranted = true, hasAdapter = true))
    }

    @Test
    fun `an unknown source name falls back to the belt`() {
        assertEquals(SessionSource.DEMO, sourceFrom("DEMO"))
        assertEquals(SessionSource.BELT, sourceFrom(null))
        assertEquals(SessionSource.BELT, sourceFrom("WIFI"))
    }

    @Test
    fun `the ongoing status says eliminated first`() {
        assertEquals("Eliminado", ongoingStatus(eliminated = true, source = SessionSource.DEMO))
        assertEquals("Demo en curso", ongoingStatus(eliminated = false, source = SessionSource.DEMO))
        assertEquals("Partida en curso", ongoingStatus(eliminated = false, source = SessionSource.BELT))
    }
}
