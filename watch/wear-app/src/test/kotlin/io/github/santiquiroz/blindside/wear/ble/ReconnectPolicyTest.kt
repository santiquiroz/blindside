package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
