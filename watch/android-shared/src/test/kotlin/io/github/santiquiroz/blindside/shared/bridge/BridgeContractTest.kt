package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.settings.RadarSettings
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// The wire contract between the watch and the phone app (plan 06). Change a literal only together with both apps.
private const val SETTINGS_JSON = """{"handedness":"LEFT","posture":"TACTICAL_RIGHT","updated_ms":1727790153123,"radars":[{"id":0,"yaw_deg":-35.0,"flip_x":true,"speed_sign":-1},{"id":1,"yaw_deg":null,"flip_x":false,"speed_sign":1}]}"""
private const val STATUS_JSON = """{"session_active":true,"link":"STREAMING","link_up":true,"radars":[{"id":0,"alive":true},{"id":1,"alive":false}],"updated_ms":1727790153000}"""
private const val LIST_JSON = """[{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":53000000,"inicio":1727790153000}]"""

class BridgeContractTest {
    private val settings = SharedSettings(
        handedness = Handedness.LEFT,
        radars = listOf(
            RadarSettings(RADAR_A, -35.0, flipX = true, speedSign = -1),
            RadarSettings(RADAR_B, null, flipX = false, speedSign = 1),
        ),
        posture = WatchPosture.TACTICAL_RIGHT,
        updatedMs = 1_727_790_153_123L,
    )
    private val status = WatchStatus(
        sessionActive = true,
        ble = BleStatus.STREAMING,
        linkUp = true,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, false)),
        updatedMs = 1_727_790_153_000L,
    )

    @Test
    fun `the settings payload is pinned`() {
        assertEquals(SETTINGS_JSON, encodeSharedSettings(settings))
        assertEquals(settings, decodeSharedSettings(SETTINGS_JSON))
    }

    @Test
    fun `the status payload is pinned`() {
        assertEquals(STATUS_JSON, encodeWatchStatus(status))
        assertEquals(status, decodeWatchStatus(STATUS_JSON))
    }

    @Test
    fun `the recording list and the pairing reply are pinned`() {
        val entry = RecordingEntry("blindside-belt-20261001-142233.bsrec", 53_000_000L, 1_727_790_153_000L)
        assertEquals(LIST_JSON, encodeRecordingList(listOf(entry)))
        assertEquals(listOf(entry), decodeRecordingList(LIST_JSON))
        assertEquals("REQUESTED", encodeOpenPairingReply(OpenPairingReply.REQUESTED).toString(Charsets.UTF_8))
    }
}
