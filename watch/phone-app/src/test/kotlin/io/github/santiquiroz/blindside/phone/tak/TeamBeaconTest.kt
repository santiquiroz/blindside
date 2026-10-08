package io.github.santiquiroz.blindside.phone.tak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamBeaconTest {
    @Test
    fun `beacon id is deterministic`() {
        assertEquals(beaconIdOf("santi"), beaconIdOf("santi"))
    }

    @Test
    fun `beacon id fits in 32 unsigned bits`() {
        listOf("santi", "jugador1", "").forEach { deviceId ->
            assertTrue(beaconIdOf(deviceId) in 0L..0xFFFF_FFFFL)
        }
    }

    @Test
    fun `different device ids give different beacon ids`() {
        assertNotEquals(beaconIdOf("santi"), beaconIdOf("jugador1"))
    }
}
