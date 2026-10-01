package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.phone.ui.radar.UPDATE_FIRMWARE_TEXT
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BeltActionsTest {
    @Test
    fun `the phone link is up only while a session streams`() {
        assertTrue(phoneLinkUp(SessionUiState(running = true, ble = BleStatus.STREAMING)))
        assertFalse(phoneLinkUp(SessionUiState(running = true, ble = BleStatus.CONNECTING)))
        assertFalse(phoneLinkUp(SessionUiState(running = false, ble = BleStatus.STREAMING)))
    }

    @Test
    fun `restarting a radar needs only a streaming link`() {
        assertNull(restartBlock(linkUp = true))
        assertEquals(ActionBlock.NOT_CONNECTED, restartBlock(linkUp = false))
    }

    @Test
    fun `identify is blocked while any device plays a game`() {
        assertEquals(ActionBlock.NOT_CONNECTED, identifyBlock(linkUp = false, purpose = SessionPurpose.DIAGNOSTIC, watchSessionActive = false))
        assertEquals(ActionBlock.SESSION_ACTIVE, identifyBlock(linkUp = true, purpose = SessionPurpose.GAME, watchSessionActive = false))
        assertEquals(ActionBlock.SESSION_ACTIVE, identifyBlock(linkUp = true, purpose = SessionPurpose.DIAGNOSTIC, watchSessionActive = true))
        assertNull(identifyBlock(linkUp = true, purpose = SessionPurpose.DIAGNOSTIC, watchSessionActive = false))
    }

    @Test
    fun `every block explains itself`() {
        ActionBlock.entries.forEach { assertTrue(actionBlockText(it).isNotBlank()) }
    }

    @Test
    fun `the pairing request always ends in a next step`() {
        assertTrue("60 s" in pairingRequestMessage(BridgeResult.Ok(OpenPairingReply.REQUESTED)))
        assertEquals(NO_WATCH_LINK_TEXT, pairingRequestMessage(BridgeResult.Ok(OpenPairingReply.NO_LINK)))
        assertTrue("BOOT" in pairingRequestMessage(BridgeResult.NoWatch))
        val failed = pairingRequestMessage(BridgeResult.Failed("sin respuesta a tiempo"))
        assertTrue("sin respuesta a tiempo" in failed && "BOOT" in failed)
    }

    @Test
    fun `a watch whose game is not running says to start it`() {
        assertTrue("inicia el radar" in NO_WATCH_LINK_TEXT && "BOOT" in NO_WATCH_LINK_TEXT)
    }

    @Test
    fun `pairing guidance depends on the belt firmware`() {
        assertEquals(PairingGuidance(PAIRING_STEPS, canAskWatch = true), pairingGuidance(LinkSupport.DUAL_LINK))
        assertEquals(PairingGuidance(UPDATE_FIRMWARE_TEXT, canAskWatch = false), pairingGuidance(LinkSupport.SINGLE_LINK))
        val unknown = pairingGuidance(LinkSupport.UNKNOWN)
        assertTrue(unknown.canAskWatch && FIRMWARE_CAUTION in unknown.text && PAIRING_STEPS in unknown.text)
    }

    @Test
    fun `the pairing steps say what to do when the belt has no free slot`() {
        assertTrue("no queda espacio" in PAIRING_STEPS)
    }

    @Test
    fun `counters and signal show only while the phone is connected`() {
        val phone = PhoneDiagnostics(infoJson = "{}", rssiDbm = -60, counters = PipelineCounters(packets = 9))
        assertEquals(phone, liveDiagnostics(phone, running = true))
        assertEquals(PhoneDiagnostics(infoJson = "{}"), liveDiagnostics(phone, running = false))
    }

    @Test
    fun `the watch line says when there is no recent status`() {
        val status = WatchStatus(true, BleStatus.STREAMING, linkUp = true, radars = listOf(SensorStatus(0, true), SensorStatus(RADAR_B, false)), updatedMs = 10_000L)
        assertTrue("sin datos" in watchStatusLine(null, nowMs = 0L))
        assertEquals("Reloj: sin datos recientes (hace 20 s)", watchStatusLine(status, nowMs = 30_000L))
        assertEquals("Reloj: partida activa · enlace bien · radar A bien, B caído", watchStatusLine(status, nowMs = 12_000L))
        assertTrue("enlace caído" in watchStatusLine(status.copy(linkUp = false), nowMs = 12_000L))
    }

    @Test
    fun `the link line names the purpose and the state`() {
        assertEquals("Celular sin conexión al cinturón", beltLinkText(SessionUiState()))
        val diagnostic = SessionUiState(running = true, purpose = SessionPurpose.DIAGNOSTIC, ble = BleStatus.STREAMING)
        assertEquals("Diagnóstico: Recibiendo datos", beltLinkText(diagnostic))
    }

    @Test
    fun `settings labels read in spanish`() {
        assertEquals("Cambia de hombro", handednessLabel(Handedness.SWITCHER))
        assertEquals("Radar B -40°", yawText(RADAR_B, -39.6))
    }
}
