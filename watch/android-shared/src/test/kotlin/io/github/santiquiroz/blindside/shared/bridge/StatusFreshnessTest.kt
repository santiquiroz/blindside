package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusFreshnessTest {
    private val status = WatchStatus(
        sessionActive = true,
        ble = BleStatus.STREAMING,
        linkUp = true,
        radars = listOf(SensorStatus(0, true)),
        updatedMs = 10_000L,
    )

    @Test
    fun `a status stays fresh for three publish periods`() {
        assertEquals(3 * STATUS_PERIOD_MS, STATUS_STALE_AFTER_MS)
        assertTrue(isStatusFresh(status, nowMs = 25_000L))
        assertFalse(isStatusFresh(status, nowMs = 25_001L))
    }

    @Test
    fun `only a fresh status can report an active session`() {
        assertTrue(watchSessionActive(status, nowMs = 12_000L))
        assertFalse(watchSessionActive(status, nowMs = 60_000L))
        assertFalse(watchSessionActive(status.copy(sessionActive = false), nowMs = 12_000L))
        assertFalse(watchSessionActive(null, nowMs = 12_000L))
    }

    @Test
    fun `a status stamped by a watch clock slightly ahead still counts as fresh`() {
        assertTrue(isStatusFresh(status, nowMs = 9_000L))
    }
}
