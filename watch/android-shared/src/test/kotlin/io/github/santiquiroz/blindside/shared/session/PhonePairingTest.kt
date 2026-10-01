package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhonePairingTest {
    private val deliveredAtMs = 1_000_000L

    @Test
    fun `a request waits for the belt only when the write was queued`() {
        assertEquals(PhonePairing.REQUESTED, pairingAfterRequest(queued = true))
        assertEquals(PhonePairing.NO_LINK, pairingAfterRequest(queued = false))
    }

    @Test
    fun `the write result says delivered or refused, never open`() {
        assertEquals(PhonePairing.DELIVERED, pairingAfterWrite(delivered = true))
        assertEquals(PhonePairing.REFUSED, pairingAfterWrite(delivered = false))
    }

    @Test
    fun `the pairing write records when it was delivered`() {
        val recorded = recordPairingWrite(SessionUiState(running = true), delivered = true, nowNanos = 5_000_000_000L)
        assertEquals(PhonePairing.DELIVERED, recorded.phonePairing)
        assertEquals(5_000L, recorded.phonePairingAtMs)
        assertTrue(recorded.running)
    }

    @Test
    fun `a delivered request reads as idle once the 60 s window is over`() {
        assertEquals(PhonePairing.DELIVERED, pairingStateAt(PhonePairing.DELIVERED, deliveredAtMs, deliveredAtMs + 59_999L))
        assertEquals(PhonePairing.IDLE, pairingStateAt(PhonePairing.DELIVERED, deliveredAtMs, deliveredAtMs + PAIRING_WINDOW_MS))
        assertEquals(PhonePairing.IDLE, pairingStateAt(PhonePairing.DELIVERED, null, deliveredAtMs))
        assertEquals(PhonePairing.REFUSED, pairingStateAt(PhonePairing.REFUSED, deliveredAtMs, deliveredAtMs + PAIRING_WINDOW_MS))
    }

    @Test
    fun `only a streaming belt game can open the window`() {
        val streaming = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.STREAMING)
        assertTrue(canOpenPairingWindow(streaming))
        assertFalse(canOpenPairingWindow(streaming.copy(source = SessionSource.DEMO)))
        assertFalse(canOpenPairingWindow(streaming.copy(ble = BleStatus.RECONNECTING)))
        assertFalse(canOpenPairingWindow(SessionUiState()))
    }
}
