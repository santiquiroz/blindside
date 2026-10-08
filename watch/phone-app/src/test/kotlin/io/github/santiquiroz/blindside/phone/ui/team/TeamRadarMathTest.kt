package io.github.santiquiroz.blindside.phone.ui.team

import io.github.santiquiroz.blindside.phone.tak.TeamContact
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.destinationOf
import io.github.santiquiroz.blindside.shared.tak.Mate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamRadarMathTest {
    private val here = GeoPoint(5.0689, -75.5174)

    private fun mateAt(bearingDeg: Double, distanceM: Double, callsign: String = "toro", ageS: Int = 3) =
        Mate(callsign, destinationOf(here, bearingDeg, distanceM), ageS)

    private fun contactAt(bearingDeg: Double, distanceM: Double, label: String = "Radar Santi 1", ageS: Int = 3) =
        TeamContact("uid-$bearingDeg-$distanceM", label, destinationOf(here, bearingDeg, distanceM), ageS)

    @Test
    fun `ally due north shows straight up when heading north`() {
        val marks = teamMarks(here, 0.0, 100.0, listOf(mateAt(0.0, 30.0)), emptyList())
        assertEquals(1, marks.size)
        assertEquals(MarkKind.ALLY, marks[0].kind)
        assertEquals("TO", marks[0].label)
        assertEquals(0.0, marks[0].screenAngleDeg, 1e-6)
        assertEquals(3, marks[0].ageS)
    }

    @Test
    fun `ally due north shows left when heading east`() {
        val marks = teamMarks(here, 90.0, 100.0, listOf(mateAt(0.0, 30.0)), emptyList())
        assertEquals(270.0, marks.single().screenAngleDeg, 1e-6)
    }

    @Test
    fun `contact at 30 m on a 50 m scale sits at 0,6 and on scale`() {
        val marks = teamMarks(here, 0.0, 50.0, emptyList(), listOf(contactAt(45.0, 30.0)))
        assertEquals(1, marks.size)
        assertEquals(MarkKind.CONTACT, marks[0].kind)
        assertEquals("Radar Santi 1", marks[0].label)
        assertEquals(45.0, marks[0].screenAngleDeg, 1e-6)
        assertEquals(0.6, marks[0].radiusFraction, 1e-6)
        assertFalse(marks[0].offScale)
        assertEquals(30.0, marks[0].distanceM, 1e-6)
    }

    @Test
    fun `contact beyond the scale clamps to the edge`() {
        val marks = teamMarks(here, 0.0, 50.0, emptyList(), listOf(contactAt(45.0, 80.0)))
        assertEquals(1.0, marks.single().radiusFraction, 1e-9)
        assertTrue(marks.single().offScale)
        assertEquals(80.0, marks.single().distanceM, 1e-6)
    }

    @Test
    fun `allies come before contacts`() {
        val marks = teamMarks(
            here, 0.0, 100.0,
            listOf(mateAt(0.0, 30.0)),
            listOf(contactAt(90.0, 30.0)),
        )
        assertEquals(listOf(MarkKind.ALLY, MarkKind.CONTACT), marks.map { it.kind })
    }

    @Test
    fun `ranges cycle through the presets`() {
        assertEquals(100.0, nextRange(50.0))
        assertEquals(250.0, nextRange(100.0))
        assertEquals(50.0, nextRange(250.0))
        assertEquals(100.0, nextRange(75.0))
    }

    @Test
    fun `fresh contacts are opaque and old ones fade to 0,3`() {
        assertEquals(1.0f, contactAlpha(0))
        assertEquals(1.0f, contactAlpha(5))
        assertEquals(0.3f, contactAlpha(30))
        assertEquals(0.3f, contactAlpha(31))
        assertEquals(0.72f, contactAlpha(15), 1e-6f)
    }

    @Test
    fun `flat phone with its top edge north heads north`() {
        assertEquals(0.0, phoneHeadingDeg(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)), 1e-6)
    }

    @Test
    fun `flat phone turned so its top edge faces east heads east`() {
        assertEquals(90.0, phoneHeadingDeg(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)), 1e-6)
    }

    @Test
    fun `upright phone facing east heads east`() {
        assertEquals(90.0, phoneHeadingDeg(floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)), 1e-6)
    }

    @Test
    fun `upright phone facing north heads north`() {
        assertEquals(0.0, phoneHeadingDeg(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)), 1e-6)
    }
}
