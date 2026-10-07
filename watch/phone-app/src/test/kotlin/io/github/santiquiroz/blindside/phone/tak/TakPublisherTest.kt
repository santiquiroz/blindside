package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.tak.GeoFix
import io.github.santiquiroz.blindside.shared.tak.Telemetry
import io.github.santiquiroz.blindside.shared.tak.TelemetryBlip
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind
import io.github.santiquiroz.blindside.shared.tactical.destinationOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TakPublisherTest {
    companion object {
        const val NOW = 1_760_000_000_000L
        val HERE = GeoPoint(5.0689, -75.5174)
        val FIX = GeoFix(HERE, 4.0)
        val IDS = TakIds("abc123", "Santi")
    }

    private fun telemetry(
        headingOk: Boolean = true,
        blips: List<TelemetryBlip> = listOf(TelemetryBlip(3, 90.0, 100.0, Confidence.BOTH)),
        points: Map<TacticalKind, GeoPoint> = emptyMap(),
    ) = Telemetry(headingOk, blips, points)

    private fun types(events: List<String>): List<String> =
        events.map { requireNotNull(parseCotEvent(it)).type }

    @Test
    fun `fresh fix and heading publishes one contact`() {
        val out = publishTelemetry(PublishState(), telemetry(), FIX, 1_000L, IDS, true, NOW)
        assertEquals(1, out.events.size)
        val event = parseCotEvent(out.events[0])
        assertNotNull(event)
        assertEquals("BLINDSIDE-abc123-C3", event!!.uid)
        assertEquals("a-u-G", event.type)
        assertEquals("Radar Santi 3", event.callsign)
        val want = destinationOf(HERE, 90.0, 100.0)
        assertNotNull(event.point)
        assertEquals(want.latDeg, event.point!!.latDeg, 1e-6)
        assertEquals(want.lonDeg, event.point.lonDeg, 1e-6)
        assertEquals(setOf("BLINDSIDE-abc123-C3"), out.state.publishedUids)
    }

    @Test
    fun `contact ce rounds accuracy plus beam spread with minimum 1`() {
        // 4.0 + 100 * sin(15 deg) = 29.88 -> 30.0
        val out = publishTelemetry(PublishState(), telemetry(), FIX, 1_000L, IDS, true, NOW)
        assertTrue(out.events[0].contains("ce=\"30.0\""), out.events[0])
        val tiny = Telemetry(true, listOf(TelemetryBlip(1, 0.0, 0.5, Confidence.BOTH)), emptyMap())
        val min = publishTelemetry(PublishState(), tiny, GeoFix(HERE, 0.1), 1_000L, IDS, true, NOW)
        assertTrue(min.events[0].contains("ce=\"1.0\""), min.events[0])
    }

    @Test
    fun `next round without the blip deletes its uid`() {
        val first = publishTelemetry(PublishState(), telemetry(), FIX, 1_000L, IDS, true, NOW)
        val second = publishTelemetry(first.state, telemetry(blips = emptyList()), FIX, 1_000L, IDS, true, NOW + 1_000)
        assertEquals(1, second.events.size)
        val event = parseCotEvent(second.events[0])
        assertNotNull(event)
        assertEquals("t-x-d-d", event!!.type)
        assertEquals("BLINDSIDE-abc123-C3", event.linkUid)
        assertTrue(second.state.publishedUids.isEmpty())
    }

    @Test
    fun `stale fix publishes nothing and deletes what was published`() {
        val state = PublishState(publishedUids = setOf("BLINDSIDE-abc123-C3"))
        val out = publishTelemetry(state, telemetry(), FIX, 10_001L, IDS, true, NOW)
        assertEquals(listOf("t-x-d-d"), types(out.events))
        assertEquals("BLINDSIDE-abc123-C3", parseCotEvent(out.events[0])!!.linkUid)
        assertTrue(out.state.publishedUids.isEmpty())
    }

    @Test
    fun `fix at exactly 10s still publishes`() {
        val out = publishTelemetry(PublishState(), telemetry(), FIX, 10_000L, IDS, true, NOW)
        assertEquals(listOf("a-u-G"), types(out.events))
    }

    @Test
    fun `publishContacts false publishes nothing and deletes what was published`() {
        val state = PublishState(publishedUids = setOf("BLINDSIDE-abc123-C3"))
        val out = publishTelemetry(state, telemetry(), FIX, 1_000L, IDS, false, NOW)
        assertEquals(listOf("t-x-d-d"), types(out.events))
        assertTrue(out.state.publishedUids.isEmpty())
    }

    @Test
    fun `no heading or no fix publishes nothing and deletes what was published`() {
        val state = PublishState(publishedUids = setOf("BLINDSIDE-abc123-C3"))
        val noHeading = publishTelemetry(state, telemetry(headingOk = false), FIX, 1_000L, IDS, true, NOW)
        assertEquals(listOf("t-x-d-d"), types(noHeading.events))
        val noFix = publishTelemetry(state, telemetry(), null, null, IDS, true, NOW)
        assertEquals(listOf("t-x-d-d"), types(noFix.events))
    }

    @Test
    fun `markers go out first time not at 10s yes at 30s`() {
        val points = mapOf(TacticalKind.BASE to HERE)
        val first = publishTelemetry(PublishState(), telemetry(points = points), FIX, 1_000L, IDS, true, NOW)
        assertEquals(listOf("a-u-G", "b-m-p-s-m"), types(first.events))
        assertEquals(NOW, first.state.lastMarkersMs)
        val second = publishTelemetry(first.state, telemetry(points = points), FIX, 1_000L, IDS, true, NOW + 10_000)
        assertEquals(listOf("a-u-G"), types(second.events))
        assertEquals(NOW, second.state.lastMarkersMs)
        val third = publishTelemetry(second.state, telemetry(points = points), FIX, 1_000L, IDS, true, NOW + 30_000)
        assertEquals(listOf("a-u-G", "b-m-p-s-m"), types(third.events))
        assertEquals(NOW + 30_000, third.state.lastMarkersMs)
    }

    @Test
    fun `markers carry kind callsign uid and color`() {
        val points = mapOf(
            TacticalKind.BASE to HERE,
            TacticalKind.SPAWN to GeoPoint(5.0691, -75.517),
            TacticalKind.OBJECTIVE to GeoPoint(5.0695, -75.516),
        )
        val out = publishTelemetry(PublishState(), telemetry(points = points), FIX, 1_000L, IDS, true, NOW)
        val markers = out.events.mapNotNull(::parseCotEvent).filter { it.type == "b-m-p-s-m" }
        assertEquals(3, markers.size)
        val byUid = markers.associateBy { it.uid }
        assertEquals("Base Santi", byUid["BLINDSIDE-abc123-BASE"]!!.callsign)
        assertEquals("Spawn Santi", byUid["BLINDSIDE-abc123-SPAWN"]!!.callsign)
        assertEquals("Objetivo Santi", byUid["BLINDSIDE-abc123-OBJECTIVE"]!!.callsign)
        assertTrue(out.events.any { it.contains("argb=\"${0xFF00C853.toInt()}\"") })
        assertTrue(out.events.any { it.contains("argb=\"${0xFF00B8D4.toInt()}\"") })
        assertTrue(out.events.any { it.contains("argb=\"${0xFFFF1744.toInt()}\"") })
    }

    @Test
    fun `markers are not tracked as published contacts`() {
        val points = mapOf(TacticalKind.BASE to HERE)
        val out = publishTelemetry(PublishState(), telemetry(points = points), FIX, 1_000L, IDS, true, NOW)
        assertEquals(setOf("BLINDSIDE-abc123-C3"), out.state.publishedUids)
    }

    @Test
    fun `no points means no markers and lastMarkersMs stays null`() {
        val out = publishTelemetry(PublishState(), telemetry(), FIX, 1_000L, IDS, true, NOW)
        assertEquals(listOf("a-u-G"), types(out.events))
        assertNull(out.state.lastMarkersMs)
    }

    @Test
    fun `events order is contacts deletes markers`() {
        val points = mapOf(TacticalKind.BASE to HERE)
        val state = PublishState(publishedUids = setOf("BLINDSIDE-abc123-C9"))
        val out = publishTelemetry(state, telemetry(points = points), FIX, 1_000L, IDS, true, NOW)
        assertEquals(listOf("a-u-G", "t-x-d-d", "b-m-p-s-m"), types(out.events))
    }
}
