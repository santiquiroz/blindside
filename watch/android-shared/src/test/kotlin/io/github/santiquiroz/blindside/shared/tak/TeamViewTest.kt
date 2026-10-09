package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamViewTest {
    private val here = GeoPoint(5.0689, -75.5174)
    private val watchFix = GeoPoint(5.0, -75.0)

    @Test
    fun `callsigns shrink to two uppercase letters or digits`() {
        assertEquals("TO", mateLabel("toro"))
        assertEquals("ÑU", mateLabel("  ñu-7"))
        assertEquals("??", mateLabel("--"))
    }

    @Test
    fun `only the six closest mates are shown in distance order`() {
        val mates = (8 downTo 1).map { i ->
            Mate("M$i", GeoPoint(here.latDeg + i * 0.001, here.lonDeg), 3)
        }
        val marks = mateMarks(mates, here)
        assertEquals(listOf("M1", "M2", "M3", "M4", "M5", "M6"), marks.map { it.label })
        assertTrue(marks.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
    }

    @Test
    fun `old and far mates are left out`() {
        val mates = listOf(
            Mate("cerca", GeoPoint(here.latDeg + 0.001, here.lonDeg), 3),
            Mate("viejo", GeoPoint(here.latDeg + 0.001, here.lonDeg), 61),
            Mate("lejos", GeoPoint(here.latDeg + 0.02, here.lonDeg), 3),
        )
        assertEquals(listOf("CE"), mateMarks(mates, here).map { it.label })
    }

    @Test
    fun `a fresh team fix wins over the watch fix`() {
        val self = GeoPoint(1.0, 2.0)
        val team = TeamUpdate(GeoFix(self, 5.0), emptyList())
        assertEquals(self, hereOf(team, 10_000L, 11_000L, watchFix))
    }

    @Test
    fun `a stale team fix falls back to the watch fix`() {
        val team = TeamUpdate(GeoFix(GeoPoint(1.0, 2.0), 5.0), emptyList())
        assertEquals(watchFix, hereOf(team, 0L, 16_000L, watchFix))
    }

    @Test
    fun `a null self falls back to the watch fix`() {
        val team = TeamUpdate(null, emptyList())
        assertEquals(watchFix, hereOf(team, 10_000L, 11_000L, watchFix))
    }

    @Test
    fun `phoneFix returns the point only when the team fix is fresh`() {
        val self = GeoPoint(1.0, 2.0)
        val team = TeamUpdate(GeoFix(self, 5.0), emptyList())
        assertEquals(self, phoneFix(team, 10_000L, 11_000L))
        assertNull(phoneFix(team, 0L, 16_000L))
        assertNull(phoneFix(team, null, 11_000L))
        assertNull(phoneFix(TeamUpdate(null, emptyList()), 10_000L, 11_000L))
        assertNull(phoneFix(null, 10_000L, 11_000L))
    }

    @Test
    fun `the team link is active only within its fresh window`() {
        assertFalse(teamLinkActive(null, 1_000L))
        assertTrue(teamLinkActive(0L, 5_000L))
        assertFalse(teamLinkActive(0L, 11_000L))
        assertFalse(teamLinkActive(5_000L, 1_000L))
    }
}
