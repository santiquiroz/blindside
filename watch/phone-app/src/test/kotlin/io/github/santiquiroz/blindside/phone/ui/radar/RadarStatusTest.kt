package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarStatusTest {
    private fun scene(linkUp: Boolean = true, eliminated: Boolean = false, blips: List<Blip> = emptyList()) = RadarScene(
        blips = blips,
        coverage = emptyList(),
        linkUp = linkUp,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, false)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = eliminated,
    )

    @Test
    fun `the live radar shows only for a running game`() {
        assertTrue(isLiveRadar(SessionUiState(running = true, purpose = SessionPurpose.GAME)))
        assertFalse(isLiveRadar(SessionUiState(running = true, purpose = SessionPurpose.DIAGNOSTIC)))
        assertFalse(isLiveRadar(SessionUiState(purpose = SessionPurpose.GAME)))
    }

    @Test
    fun `sensor chips report the link, both radars and both imus`() {
        val expected = listOf(
            SensorChip("Enlace", true),
            SensorChip("Radar A", true),
            SensorChip("Radar B", false),
            SensorChip("IMU A", true),
            SensorChip("IMU B", true),
        )
        assertEquals(expected, sensorChips(scene()))
        assertTrue(sensorChips(null).none { it.ok })
    }

    @Test
    fun `searching tells the user how to pair the phone and when there is no room`() {
        assertTrue("Emparejar celular" in phoneLinkLabel(BleStatus.SEARCHING))
        assertTrue("no queda espacio" in phoneLinkLabel(BleStatus.SEARCHING))
        assertTrue("clave" in phoneLinkLabel(BleStatus.PAIRING))
    }

    @Test
    fun `the banner explains a refused start first, then the link, and hides once data flows`() {
        val blocked = SessionUiState(startError = StartError.BLUETOOTH_PERMISSION_MISSING, ble = BleStatus.SEARCHING)
        assertEquals(BLUETOOTH_DENIED_TEXT, radarBanner(blocked))
        assertEquals(phoneLinkLabel(BleStatus.SEARCHING), radarBanner(SessionUiState(running = true, ble = BleStatus.SEARCHING)))
        assertNull(radarBanner(SessionUiState(running = true, ble = BleStatus.STREAMING, scene = scene())))
    }

    @Test
    fun `the header line names the recording or its failure`() {
        assertEquals("Grabando: r.bsrec", recordingLine(SessionUiState(recordingName = "r.bsrec")))
        assertEquals("La grabación falló; el radar sigue.", recordingLine(SessionUiState(recordingName = "r.bsrec", recordingFailed = true)))
        assertNull(recordingLine(SessionUiState()))
    }

    @Test
    fun `the idle hint guides the first pairing, warns about old firmware and mentions a diagnostic in progress`() {
        assertTrue("Emparejar celular" in idleRadarHint(beltPaired = false, purpose = null, linkSupport = LinkSupport.UNKNOWN))
        assertTrue("emparejado" in idleRadarHint(beltPaired = true, purpose = null, linkSupport = LinkSupport.DUAL_LINK))
        assertTrue(UPDATE_FIRMWARE_TEXT in idleRadarHint(beltPaired = true, purpose = null, linkSupport = LinkSupport.SINGLE_LINK))
        assertTrue("diagnóstico" in idleRadarHint(beltPaired = true, purpose = SessionPurpose.DIAGNOSTIC, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `screen readers hear what the radar shows`() {
        assertEquals("Radar sin enlace", radarDescription(null))
        assertEquals("Radar en modo eliminado", radarDescription(scene(eliminated = true)))
        val one = Blip(1, 0.0, 2.0, Confidence.BOTH, 0, false)
        assertEquals("Radar con 1 contactos", radarDescription(scene(blips = listOf(one))))
    }
}
