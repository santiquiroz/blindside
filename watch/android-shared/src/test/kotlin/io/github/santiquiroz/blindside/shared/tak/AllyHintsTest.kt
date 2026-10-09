package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.destinationOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AllyHintsTest {
    private val here = GeoPoint(5.0, -75.0)
    private val nowMs = 100_000L

    private fun blip(id: Int, bearingDeg: Double, rangeM: Double) =
        Blip(id, bearingDeg, rangeM, Confidence.BOTH, 0L, false)

    private fun mateAt(bearingDeg: Double, distanceM: Double, ageS: Int = 3) =
        Mate("Toro", destinationOf(here, bearingDeg, distanceM), ageS)

    private fun gpsOnly(blips: List<Blip>, mates: List<Mate>, heading: Double?): Set<Int> =
        likelyAllyIds(blips, mates, here, heading, emptyMap(), null, nowMs)

    private fun bleOnly(blips: List<Blip>, beacons: Map<Long, BeaconSeen>, ownBeacon: Long? = null): Set<Int> =
        likelyAllyIds(blips, emptyList(), null, null, beacons, ownBeacon, nowMs)

    @Test
    fun `a lone mate marks the single blip in its sector`() {
        val ids = gpsOnly(listOf(blip(1, 10.0, 8.0)), listOf(mateAt(0.0, 10.0)), 0.0)
        assertEquals(setOf(1), ids)
    }

    @Test
    fun `a mate with two blips in its sector marks nothing`() {
        val ids = gpsOnly(listOf(blip(1, 10.0, 8.0), blip(2, 350.0, 9.0)), listOf(mateAt(0.0, 10.0)), 0.0)
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `two mates together mark both blips in their sector`() {
        val ids = gpsOnly(
            listOf(blip(1, 10.0, 8.0), blip(2, 350.0, 9.0)),
            listOf(mateAt(0.0, 10.0), mateAt(10.0, 10.0)),
            0.0,
        )
        assertEquals(setOf(1, 2), ids)
    }

    @Test
    fun `no body heading marks nothing by gps`() {
        val ids = gpsOnly(listOf(blip(1, 10.0, 8.0)), listOf(mateAt(0.0, 10.0)), null)
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `a mate beyond 15 m marks nothing`() {
        val ids = gpsOnly(listOf(blip(1, 0.0, 8.0)), listOf(mateAt(0.0, 20.0)), 0.0)
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `one near beacon and one close blip marks it`() {
        val ids = bleOnly(listOf(blip(1, 90.0, 3.0)), mapOf(42L to BeaconSeen(-60.0, nowMs)))
        assertEquals(setOf(1), ids)
    }

    @Test
    fun `one beacon and two close blips marks nothing`() {
        val ids = bleOnly(
            listOf(blip(1, 90.0, 3.0), blip(2, 270.0, 3.5)),
            mapOf(42L to BeaconSeen(-60.0, nowMs)),
        )
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `the own beacon is ignored`() {
        val ids = bleOnly(listOf(blip(1, 90.0, 3.0)), mapOf(7L to BeaconSeen(-60.0, nowMs)), ownBeacon = 7L)
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `a stale beacon is ignored`() {
        val ids = bleOnly(listOf(blip(1, 90.0, 3.0)), mapOf(42L to BeaconSeen(-60.0, nowMs - 6_000L)))
        assertEquals(emptySet<Int>(), ids)
    }

    @Test
    fun `smoothed beacon averages rssi with alpha 0_3`() {
        val first = smoothedBeacon(null, -60, nowMs)
        assertEquals(-60.0, first.rssiDbm, 1e-9)
        val second = smoothedBeacon(first, -70, nowMs)
        assertEquals(-63.0, second.rssiDbm, 1e-9)
        assertEquals(nowMs, second.lastSeenMs)
    }

    @Test
    fun `a blip next to a station mate is not a likely ally`() {
        val station = Mate("Mando", destinationOf(here, 0.0, 10.0), 3, MateKind.STATION)
        assertEquals(emptySet<Int>(), gpsOnly(listOf(blip(1, 10.0, 8.0)), listOf(station), 0.0))
    }

    @Test
    fun `a blip next to a player mate is still a likely ally`() {
        assertEquals(setOf(1), gpsOnly(listOf(blip(1, 10.0, 8.0)), listOf(mateAt(0.0, 10.0)), 0.0))
    }
}
