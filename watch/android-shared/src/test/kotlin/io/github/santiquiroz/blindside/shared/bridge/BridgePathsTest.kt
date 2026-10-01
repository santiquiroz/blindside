package io.github.santiquiroz.blindside.shared.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BridgePathsTest {
    @Test
    fun `paths, keys and codes match the bridge contract`() {
        assertEquals("/recordings/list", RECORDINGS_LIST_PATH)
        assertEquals("/recordings/get", RECORDINGS_GET_PATH)
        assertEquals("/settings", SETTINGS_PATH)
        assertEquals("/status", STATUS_PATH)
        assertEquals("/belt/open-pairing", OPEN_PAIRING_PATH)
        assertEquals(5_000L, STATUS_PERIOD_MS)
        assertEquals("json", DATA_JSON_KEY)
        assertEquals(1, RECORDING_REFUSED_CODE)
    }
}
