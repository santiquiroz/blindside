package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.session.StartError
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeLabelsTest {
    @Test
    fun `every link status has a label`() {
        assertTrue(BleStatus.entries.all { bleStatusLabel(it).isNotBlank() })
    }

    @Test
    fun `demo sessions are headlined as demo`() {
        assertEquals("Demo", sessionHeadline(SessionUiState(running = true, source = SessionSource.DEMO)))
        assertEquals(bleStatusLabel(BleStatus.STREAMING), sessionHeadline(SessionUiState(running = true, ble = BleStatus.STREAMING)))
    }

    @Test
    fun `a halted link gets a short headline and the spec message below it`() {
        assertEquals(LINK_HALTED_HEADLINE, sessionHeadline(SessionUiState(running = true, ble = BleStatus.BOND_LOST)))
        assertEquals(BOND_LOST_MESSAGE, bleStatusLabel(BleStatus.BOND_LOST))
        assertEquals("Clave incorrecta o ventana cerrada", bleStatusLabel(BleStatus.PAIRING_FAILED))
        assertEquals("MTU insuficiente", bleStatusLabel(BleStatus.MTU_TOO_LOW))
    }

    @Test
    fun `every start error explains itself`() {
        assertTrue(StartError.entries.all { startErrorMessage(it).isNotBlank() })
    }

    @Test
    fun `the home offers starting the radar and the settings`() {
        assertEquals("Iniciar radar", START_RADAR_LABEL)
        assertEquals("Ajustes", SETTINGS_ENTRY_LABEL)
    }

    @Test
    fun `stopping asks for a second tap`() {
        assertEquals("Detener partida", stopLabel(confirming = false))
        assertEquals("¿Detener? Toca otra vez", stopLabel(confirming = true))
    }

    @Test
    fun `yaw labels round and name the radar`() {
        assertEquals("A -40°", yawLabel(RADAR_A, -40.4))
        assertEquals("B 25°", yawLabel(RADAR_B, 24.6))
    }

    @Test
    fun `handedness labels are distinct`() {
        assertEquals(Handedness.entries.size, Handedness.entries.map(::handednessLabel).toSet().size)
    }

    @Test
    fun `speed signs show their sign`() {
        assertEquals("+1", signLabel(1))
        assertEquals("-1", signLabel(-1))
    }
}
