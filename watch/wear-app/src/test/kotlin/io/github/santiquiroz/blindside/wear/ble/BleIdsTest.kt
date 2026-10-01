package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BleIdsTest {
    @Test
    fun `session active writes command four with the flag`() {
        assertEquals(listOf<Byte>(4, 1), sessionActiveCommand(true).toList())
        assertEquals(listOf<Byte>(4, 0), sessionActiveCommand(false).toList())
    }

    @Test
    fun `belt names start with Blindside dash`() {
        assertTrue(isBlindsideName("Blindside-3F2A"))
        assertFalse(isBlindsideName("Galaxy Buds"))
        assertFalse(isBlindsideName(null))
    }

    @Test
    fun `uuids match the contracts`() {
        assertEquals("569f3867-024f-4498-a979-90a762ad3593", SERVICE_UUID.toString())
        assertEquals("37869398-ecc2-4915-90a1-13d39d708ad5", STREAM_UUID.toString())
        assertEquals("278b9369-d8ac-4eda-868b-7bfd0dea5dc6", INFO_UUID.toString())
        assertEquals("725c9a6e-0c7b-45d2-bef6-48c03be7c092", CONTROL_UUID.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", CCCD_UUID.toString())
    }
}
