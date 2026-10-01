package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.wear.ble.BleStatus
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
    fun `stopping remembers the recording that just ended`() {
        val running = SessionUiState(running = true, ble = BleStatus.STREAMING, recordingName = "new.bsrec")
        val stopped = stoppedState(running)
        assertFalse(stopped.running)
        assertEquals(BleStatus.IDLE, stopped.ble)
        assertEquals("new.bsrec", stopped.lastRecordingName)
    }

    @Test
    fun `a blocked start reports why`() {
        val blocked = blockedState(SessionUiState(), StartError.BLUETOOTH_PERMISSION_MISSING)
        assertFalse(blocked.running)
        assertEquals(StartError.BLUETOOTH_PERMISSION_MISSING, blocked.startError)
    }
}
