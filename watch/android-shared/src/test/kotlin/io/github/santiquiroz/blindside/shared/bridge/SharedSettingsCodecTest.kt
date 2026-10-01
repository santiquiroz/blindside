package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.shared.settings.RadarSettings
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val RADAR_A_JSON = """{"id":0,"yaw_deg":-35.0,"flip_x":true,"speed_sign":1}"""
private const val RADAR_B_JSON = """{"id":1,"yaw_deg":null,"flip_x":false,"speed_sign":-1}"""

class SharedSettingsCodecTest {
    private val shared = SharedSettings(
        handedness = Handedness.LEFT,
        radars = listOf(
            RadarSettings(RADAR_A, -35.0, flipX = true, speedSign = -1),
            RadarSettings(RADAR_B, null, flipX = false, speedSign = 1),
        ),
        posture = WatchPosture.TACTICAL_RIGHT,
        updatedMs = 1_727_790_153_123L,
    )

    private fun payload(hand: String = "\"LEFT\"", posture: String = "\"NORMAL\"", radarA: String = RADAR_A_JSON): String =
        """{"handedness":$hand,"posture":$posture,"updated_ms":5,"radars":[$radarA,$RADAR_B_JSON]}"""

    @Test
    fun `shared settings survive encode and decode`() {
        assertEquals(shared, decodeSharedSettings(encodeSharedSettings(shared)))
    }

    @Test
    fun `a payload missing a radar, the stamp or json is rejected`() {
        assertNull(decodeSharedSettings(encodeSharedSettings(shared.copy(radars = shared.radars.take(1)))))
        assertNull(decodeSharedSettings("""{"handedness":"LEFT","posture":"NORMAL","radars":[$RADAR_A_JSON,$RADAR_B_JSON]}"""))
        assertNull(decodeSharedSettings("garbage"))
    }

    @Test
    fun `an out of range yaw is clamped`() {
        val decoded = decodeSharedSettings(payload(radarA = """{"id":0,"yaw_deg":500.0,"flip_x":true,"speed_sign":1}"""))!!
        assertEquals(RadarSettings(RADAR_A, 90.0, flipX = true, speedSign = 1), decoded.radars[0])
        assertEquals(RadarSettings(RADAR_B, null, flipX = false, speedSign = -1), decoded.radars[1])
    }

    @Test
    fun `an unknown or missing hand or posture is rejected`() {
        assertNull(decodeSharedSettings(payload(hand = "\"AMBIDEXTROUS\"")))
        assertNull(decodeSharedSettings(payload(hand = "null")))
        assertNull(decodeSharedSettings(payload(posture = "\"UPSIDE_DOWN\"")))
        assertNull(decodeSharedSettings("""{"posture":"NORMAL","updated_ms":5,"radars":[$RADAR_A_JSON,$RADAR_B_JSON]}"""))
    }

    @Test
    fun `a partial or malformed radar is rejected`() {
        listOf(
            """{"id":0}""",
            """{"id":0,"flip_x":true,"speed_sign":1}""",
            """{"id":0,"yaw_deg":"x","flip_x":true,"speed_sign":1}""",
            """{"id":0,"yaw_deg":null,"flip_x":"yes","speed_sign":1}""",
            """{"id":0,"yaw_deg":null,"speed_sign":1}""",
            """{"id":0,"yaw_deg":null,"flip_x":true,"speed_sign":7}""",
            """{"id":0,"yaw_deg":null,"flip_x":true,"speed_sign":0.5}""",
            """{"id":0.5,"yaw_deg":null,"flip_x":true,"speed_sign":1}""",
            RADAR_B_JSON,
        ).forEach { radar -> assertNull(decodeSharedSettings(payload(radarA = radar)), radar) }
    }
}
