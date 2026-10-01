package io.github.santiquiroz.blindside.shared.ble

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BeltCommandsTest {
    @Test
    fun `restart radar carries the radar id`() {
        assertArrayEquals(byteArrayOf(0x01, 0x00), commandBytes(BeltCommand.RestartRadar(0)))
        assertArrayEquals(byteArrayOf(0x01, 0x01), commandBytes(BeltCommand.RestartRadar(1)))
    }

    @Test
    fun `identify is a single byte`() {
        assertArrayEquals(byteArrayOf(0x03), commandBytes(BeltCommand.Identify))
    }

    @Test
    fun `set role sends 06 with the firmware role code`() {
        assertArrayEquals(byteArrayOf(0x06, 0x00), commandBytes(BeltCommand.SetRole(BeltRole.WATCH)))
        assertArrayEquals(byteArrayOf(0x06, 0x01), commandBytes(BeltCommand.SetRole(BeltRole.PHONE)))
    }

    @Test
    fun `open pairing window is command five alone`() {
        assertArrayEquals(byteArrayOf(0x05), commandBytes(BeltCommand.OpenPairingWindow))
    }

    @Test
    fun `the watch connects fast and settles balanced`() {
        assertEquals(LinkPriority.HIGH, connectPriorityFor(BeltRole.WATCH))
        assertEquals(LinkPriority.BALANCED, settledPriorityFor(BeltRole.WATCH))
    }

    @Test
    fun `the phone never settles a priority so the belt's 60 to 100 ms request stands`() {
        assertEquals(LinkPriority.BALANCED, connectPriorityFor(BeltRole.PHONE))
        assertNull(settledPriorityFor(BeltRole.PHONE))
    }
}
