package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionStoreTest {
    @Test
    fun `starting keeps only the previous recording name`() {
        val previous = SessionUiState(lastRecordingName = "old.bsrec", startError = StartError.BLUETOOTH_UNAVAILABLE)
        val started = startedState(previous, SessionSource.BELT)
        assertTrue(started.running)
        assertEquals(SessionSource.BELT, started.source)
        assertEquals("old.bsrec", started.lastRecordingName)
        assertNull(started.startError)
    }

    @Test
    fun `a new session forgets the last pairing request`() {
        val previous = SessionUiState(phonePairing = PhonePairing.DELIVERED, phonePairingAtMs = 7L)
        val started = startedState(previous, SessionSource.BELT)
        assertEquals(PhonePairing.IDLE, started.phonePairing)
        assertNull(started.phonePairingAtMs)
    }

    @Test
    fun `stopping remembers the recording that just ended`() {
        val running = SessionUiState(running = true, ble = BleStatus.STREAMING, recordingName = "new.bsrec")
        val stopped = stoppedState(running)
        assertFalse(stopped.running)
        assertEquals(BleStatus.IDLE, stopped.ble)
        assertEquals("new.bsrec", stopped.lastRecordingName)
    }

    @Test
    fun `toggling eliminated flips only that flag`() {
        val playing = SessionUiState(running = true, ble = BleStatus.STREAMING, recordingName = "new.bsrec")
        val hit = eliminatedToggled(playing)
        assertEquals(playing.copy(eliminated = true), hit)
        assertEquals(playing, eliminatedToggled(hit))
    }

    @Test
    fun `a new session never starts eliminated`() {
        val leftOver = SessionUiState(eliminated = true)
        assertFalse(startedState(leftOver, SessionSource.BELT).eliminated)
    }

    @Test
    fun `a blocked start reports why`() {
        val blocked = blockedState(SessionUiState(), StartError.BLUETOOTH_PERMISSION_MISSING)
        assertFalse(blocked.running)
        assertEquals(StartError.BLUETOOTH_PERMISSION_MISSING, blocked.startError)
    }
}
