package io.github.santiquiroz.blindside.shared.tactical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TacticalGeoTest {
    private val origin = GeoPoint(5.0689, -75.5174)   // Manizales

    @Test
    fun `due north and due east bearings come out at zero and ninety`() {
        assertEquals(0.0, bearingDeg(origin, GeoPoint(origin.latDeg + 0.01, origin.lonDeg)), 0.5)
        assertEquals(90.0, bearingDeg(origin, GeoPoint(origin.latDeg, origin.lonDeg + 0.01)), 0.5)
    }

    @Test
    fun `distance over a short hop matches the haversine to the metre and is zero at the same point`() {
        assertEquals(0.0, distanceM(origin, origin), 1e-6)
        assertTrue(distanceM(origin, GeoPoint(origin.latDeg + 0.001, origin.lonDeg)) in 110.0..112.0)
    }

    @Test
    fun `the wedge angle is the bearing minus the azimuth so it tracks north as the body turns`() {
        assertEquals(0f, wedgeScreenAngleDeg(90.0, 90.0), 1e-4f)
        assertEquals(315f, wedgeScreenAngleDeg(0.0, 45.0), 1e-4f)
    }

    @Test
    fun `an identical point gives a defined bearing, never NaN`() {
        assertTrue(bearingDeg(origin, origin) in 0.0..360.0)
    }

    @Test
    fun `the distance label is metres below a kilometre and kilometres above`() {
        assertEquals("040 m", tacticalDistanceLabel(40.4))
        assertEquals("1.2 km", tacticalDistanceLabel(1_240.0))
    }

    @Test
    fun `a hundred metres east round-trips in distance and bearing`() {
        val dest = destinationOf(origin, 90.0, 100.0)
        assertEquals(100.0, distanceM(origin, dest), 0.01)
        assertEquals(90.0, bearingDeg(origin, dest), 0.01)
    }

    @Test
    fun `north and southwest hops round-trip too`() {
        val north = destinationOf(origin, 0.0, 100.0)
        assertEquals(100.0, distanceM(origin, north), 0.01)
        assertEquals(0.0, bearingDeg(origin, north), 0.01)
        val southWest = destinationOf(origin, 225.0, 100.0)
        assertEquals(100.0, distanceM(origin, southWest), 0.01)
        assertEquals(225.0, bearingDeg(origin, southWest), 0.01)
    }

    @Test
    fun `a zero hop returns the same point`() {
        assertEquals(origin, destinationOf(origin, 90.0, 0.0))
    }
}
