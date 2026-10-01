package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.stoppedState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WatchStatusTest {
    private val scene = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = true,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, false)),
        imus = emptyList(),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )
    private val running = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.STREAMING, scene = scene)

    @Test
    fun `the status reflects the running session`() {
        val expected = WatchStatus(sessionActive = true, ble = BleStatus.STREAMING, linkUp = true, radars = scene.radars, updatedMs = 7L)
        assertEquals(expected, watchStatusOf(running, nowMs = 7L))
    }

    @Test
    fun `a stopped session publishes an inactive status`() {
        val status = watchStatusOf(stoppedState(running), nowMs = 8L)
        assertFalse(status.sessionActive)
        assertFalse(status.linkUp)
        assertEquals(emptyList<SensorStatus>(), status.radars)
    }

    @Test
    fun `the status survives encode and decode`() {
        val status = watchStatusOf(running, nowMs = 1_727_790_153_000L)
        assertEquals(status, decodeWatchStatus(encodeWatchStatus(status)))
    }

    @Test
    fun `a malformed status decodes to null`() {
        assertNull(decodeWatchStatus("{}"))
        assertNull(decodeWatchStatus("[1,2]"))
        assertNull(decodeWatchStatus("nope"))
    }
}
