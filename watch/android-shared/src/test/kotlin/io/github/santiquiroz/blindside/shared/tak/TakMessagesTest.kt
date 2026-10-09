package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TakMessagesTest {
    private val base = GeoPoint(5.0689, -75.5174)
    private val spawn = GeoPoint(5.0695, -75.518)
    private val objective = GeoPoint(5.07, -75.519)

    @Test
    fun `telemetry round-trips with blips and all three point kinds`() {
        val original = Telemetry(
            headingOk = true,
            blips = listOf(
                TelemetryBlip(3, 127.5, 4.2, Confidence.BOTH),
                TelemetryBlip(7, 10.0, 12.5, Confidence.SINGLE),
            ),
            points = mapOf(
                TacticalKind.BASE to base,
                TacticalKind.SPAWN to spawn,
                TacticalKind.OBJECTIVE to objective,
            ),
        )
        assertEquals(original, decodeTelemetry(encodeTelemetry(original)))
    }

    @Test
    fun `telemetry round-trips with empty blips`() {
        val original = Telemetry(false, emptyList(), mapOf(TacticalKind.BASE to base))
        assertEquals(original, decodeTelemetry(encodeTelemetry(original)))
    }

    @Test
    fun `team round-trips with self and mates`() {
        val original = TeamUpdate(
            GeoFix(base, 4.0),
            listOf(
                Mate("Toro", GeoPoint(5.0691, -75.517), 3),
                Mate("Lince 2", spawn, 12),
            ),
        )
        assertEquals(original, decodeTeamUpdate(encodeTeamUpdate(original)))
    }

    @Test
    fun `team round-trips with null self`() {
        val original = TeamUpdate(null, listOf(Mate("Toro", base, 1)))
        assertEquals(original, decodeTeamUpdate(encodeTeamUpdate(original)))
    }

    @Test
    fun `team round-trips with an unsigned beacon id above int range`() {
        val original = TeamUpdate(GeoFix(base, 4.0), listOf(Mate("Toro", base, 1)), me = 3_000_000_000L)
        assertEquals(original, decodeTeamUpdate(encodeTeamUpdate(original)))
    }

    @Test
    fun `team json without me decodes to null`() {
        val decoded = requireNotNull(decodeTeamUpdate("{\"v\":1,\"self\":null,\"mates\":[]}"))
        assertNull(decoded.me)
    }

    @Test
    fun `telemetry decoders reject garbage and wrong versions`() {
        assertNull(decodeTelemetry("nope"))
        assertNull(decodeTelemetry("{\"v\":2}"))
        assertNull(decodeTelemetry("{\"v\":1}"))
        assertNull(decodeTeamUpdate("nope"))
        assertNull(decodeTeamUpdate("{\"v\":2}"))
    }

    @Test
    fun `a blip with unknown confidence is skipped and the rest survive`() {
        val json = "{\"v\":1,\"headingOk\":true," +
            "\"blips\":[{\"id\":1,\"bearing\":10.0,\"range\":5.0,\"conf\":\"ZZZ\"}," +
            "{\"id\":2,\"bearing\":20.0,\"range\":6.0,\"conf\":\"BOTH\"}],\"points\":{}}"
        val decoded = decodeTelemetry(json)
        assertEquals(listOf(TelemetryBlip(2, 20.0, 6.0, Confidence.BOTH)), decoded?.blips)
    }

    @Test
    fun `an unknown point key is skipped without dropping the message`() {
        val json = "{\"v\":1,\"headingOk\":true,\"blips\":[]," +
            "\"points\":{\"BASE\":[5.0,-75.0],\"NOPE\":[1.0,2.0]}}"
        assertEquals(mapOf(TacticalKind.BASE to GeoPoint(5.0, -75.0)), decodeTelemetry(json)?.points)
    }

    @Test
    fun `a mate without callsign is skipped and the rest survive`() {
        val json = "{\"v\":1,\"self\":null,\"mates\":[" +
            "{\"lat\":5.0,\"lon\":-75.0,\"age\":3}," +
            "{\"cs\":\"Ok\",\"lat\":5.1,\"lon\":-75.1,\"age\":4}]}"
        assertEquals(listOf(Mate("Ok", GeoPoint(5.1, -75.1), 4)), decodeTeamUpdate(json)?.mates)
    }

    @Test
    fun `a station mate round trips with its kind`() {
        val original = TeamUpdate(null, listOf(Mate("Mando", base, 2, MateKind.STATION), Mate("Toro", base, 1)))
        assertEquals(original, decodeTeamUpdate(encodeTeamUpdate(original)))
    }

    @Test
    fun `a mate without a kind decodes as a player`() {
        val json = "{\"v\":1,\"self\":null,\"me\":null,\"mates\":[" +
            "{\"cs\":\"Ok\",\"lat\":5.1,\"lon\":-75.1,\"age\":4}]}"
        assertEquals(MateKind.PLAYER, decodeTeamUpdate(json)?.mates?.single()?.kind)
    }

    @Test
    fun `an unknown kind decodes as a player`() {
        val json = "{\"v\":1,\"self\":null,\"me\":null,\"mates\":[" +
            "{\"cs\":\"Ok\",\"lat\":5.1,\"lon\":-75.1,\"age\":4,\"k\":\"zz\"}]}"
        assertEquals(MateKind.PLAYER, decodeTeamUpdate(json)?.mates?.single()?.kind)
    }

    @Test
    fun `a player is encoded without a kind field`() {
        val json = encodeTeamUpdate(TeamUpdate(null, listOf(Mate("Toro", base, 1))))
        assertEquals(false, json.contains("\"k\""))
    }
}
