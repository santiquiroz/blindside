package io.github.santiquiroz.blindside.shared.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconnectPolicyTest {
    private val lost = ReconnectState(lostAtMs = 0L)

    @Test
    fun `the pending connection is left alone for the first ten seconds`() {
        assertNull(nextReconnectAction(lost, 9_999L))
    }

    @Test
    fun `a direct attempt follows after ten seconds`() {
        assertEquals(ReconnectAction.DIRECT_CONNECT, nextReconnectAction(lost, 10_000L))
    }

    @Test
    fun `direct attempts are spaced twenty seconds apart`() {
        val tried = recordAction(lost, ReconnectAction.DIRECT_CONNECT, 10_000L)
        assertNull(nextReconnectAction(tried, 29_999L))
    }

    @Test
    fun `a filtered scan is tried after thirty seconds`() {
        val tried = recordAction(lost, ReconnectAction.DIRECT_CONNECT, 10_000L)
        assertEquals(ReconnectAction.SCAN, nextReconnectAction(tried, 30_000L))
    }

    @Test
    fun `scans are spaced thirty seconds apart`() {
        val state = ReconnectState(lostAtMs = 0L, lastDirectAtMs = 50_000L, lastScanAtMs = 30_000L)
        assertNull(nextReconnectAction(state, 59_999L))
        assertEquals(ReconnectAction.SCAN, nextReconnectAction(state, 60_000L))
    }

    @Test
    fun `recording an action only touches its own timestamp`() {
        val scanned = recordAction(lost, ReconnectAction.SCAN, 31_000L)
        assertEquals(ReconnectState(lostAtMs = 0L, lastDirectAtMs = null, lastScanAtMs = 31_000L), scanned)
    }

    @Test
    fun `a connected attempt in setup is never replaced`() {
        val waitingForPasskey = ConnectionAttempt(pairing = true, connected = true)
        assertFalse(mayReplaceAttempt(waitingForPasskey, linkUp = false, directPending = false))
        assertFalse(mayReplaceAttempt(ConnectionAttempt(connected = true), linkUp = false, directPending = false))
    }

    @Test
    fun `an attempt still waiting to connect may be replaced`() {
        assertTrue(mayReplaceAttempt(ConnectionAttempt(), linkUp = false, directPending = false))
    }

    @Test
    fun `a pending direct attempt or a live link is left alone`() {
        assertFalse(mayReplaceAttempt(ConnectionAttempt(), linkUp = false, directPending = true))
        assertFalse(mayReplaceAttempt(ConnectionAttempt(connected = true, subscribed = true), linkUp = true, directPending = false))
    }
}
