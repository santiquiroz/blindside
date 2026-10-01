package io.github.santiquiroz.blindside.phone

import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.shared.radar.MAX_RANGE_M
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun `the phone module sees radar-core and android-shared`() {
        assertEquals(1, RecordType.BLE_PACKET.code)
        assertEquals(6.0, MAX_RANGE_M)
        assertFalse(SessionUiState().running)
    }
}
