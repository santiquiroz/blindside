package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.radar.CONNECTING_TO_BELT_LABEL
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError
import io.github.santiquiroz.blindside.wear.ui.bleStatusLabel
import io.github.santiquiroz.blindside.wear.ui.startErrorMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RadarLinkMessageTest {
    private val liveScene = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = true,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )

    @Test
    fun `a belt game says it is connecting until the link is up`() {
        listOf(BleStatus.IDLE, BleStatus.CONNECTING, BleStatus.STREAMING).forEach { status ->
            assertEquals(CONNECTING_TO_BELT_LABEL, linkMessage(SessionSource.BELT, status, linkUp = false))
        }
        assertNull(linkMessage(SessionSource.BELT, BleStatus.STREAMING, linkUp = true))
    }

    @Test
    fun `searching, pairing and halted links keep their own instructions`() {
        listOf(BleStatus.SEARCHING, BleStatus.PAIRING, BleStatus.RECONNECTING, BleStatus.BOND_LOST, BleStatus.BLUETOOTH_OFF).forEach { status ->
            assertEquals(bleStatusLabel(status), linkMessage(SessionSource.BELT, status, linkUp = false))
        }
    }

    @Test
    fun `demo and idle screens have no link message`() {
        assertNull(linkMessage(SessionSource.DEMO, BleStatus.IDLE, linkUp = false))
        assertNull(linkMessage(null, BleStatus.IDLE, linkUp = false))
    }

    @Test
    fun `the radar of a starting belt game says it is connecting`() {
        val starting = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.IDLE)
        assertEquals(CONNECTING_TO_BELT_LABEL, radarMessage(starting))
        assertNull(radarMessage(starting.copy(ble = BleStatus.STREAMING, scene = liveScene)))
    }

    @Test
    fun `a refused start is explained on the radar`() {
        val refused = SessionUiState(startError = StartError.BLUETOOTH_UNAVAILABLE)
        assertEquals(startErrorMessage(StartError.BLUETOOTH_UNAVAILABLE), radarMessage(refused))
    }
}
