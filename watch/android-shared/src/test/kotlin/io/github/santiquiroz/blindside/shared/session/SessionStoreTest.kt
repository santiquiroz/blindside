package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.tak.GeoFix
import io.github.santiquiroz.blindside.shared.tak.Mate
import io.github.santiquiroz.blindside.shared.tak.TeamUpdate
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
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
    fun `a blocked start reports why`() {
        val blocked = blockedState(SessionUiState(), StartError.BLUETOOTH_PERMISSION_MISSING)
        assertFalse(blocked.running)
        assertEquals(StartError.BLUETOOTH_PERMISSION_MISSING, blocked.startError)
    }

    @Test
    fun `only a running session has an active recording`() {
        assertEquals("a.bsrec", activeRecordingName(SessionUiState(running = true, recordingName = "a.bsrec")))
        assertNull(activeRecordingName(SessionUiState(running = false, recordingName = "a.bsrec")))
    }

    @Test
    fun `a started session remembers its purpose and stopping forgets it`() {
        val diagnostic = startedState(SessionUiState(), SessionSource.BELT, SessionPurpose.DIAGNOSTIC)
        assertEquals(SessionPurpose.DIAGNOSTIC, diagnostic.purpose)
        assertEquals(SessionPurpose.GAME, startedState(SessionUiState(), SessionSource.BELT).purpose)
        assertNull(stoppedState(diagnostic).purpose)
    }

    @Test
    fun `anchorHeading without a scene leaves the state unchanged`() {
        SessionStore.update { SessionUiState() }
        val before = SessionStore.state.value
        SessionStore.anchorHeading(10.0, 1_000L)
        assertEquals(before, SessionStore.state.value)
        SessionStore.update { SessionUiState() }
    }

    @Test
    fun `anchorHeading stores the offset between front heading and body yaw`() {
        SessionStore.update { SessionUiState(scene = sceneWithYaw(30.0)) }
        SessionStore.anchorHeading(10.0, 1_000L)
        assertEquals(340.0, SessionStore.state.value.headingAnchor?.offsetDeg)
        assertEquals(1_000L, SessionStore.state.value.headingAnchor?.atNanos)
        SessionStore.update { SessionUiState() }
    }

    @Test
    fun `receiveTeam stores the update and its time`() {
        SessionStore.update { SessionUiState() }
        val update = TeamUpdate(GeoFix(GeoPoint(5.0, -75.0), 4.0), listOf(Mate("Toro", GeoPoint(5.0, -75.0), 3)))
        SessionStore.receiveTeam(update, 7L)
        assertEquals(update, SessionStore.state.value.team)
        assertEquals(7L, SessionStore.state.value.teamAtMs)
        SessionStore.update { SessionUiState() }
    }

    private fun sceneWithYaw(yawDeg: Double) = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = true,
        radars = emptyList(),
        imus = emptyList(),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
        bodyYawDeg = yawDeg,
        yawFromBelt = true,
    )
}
