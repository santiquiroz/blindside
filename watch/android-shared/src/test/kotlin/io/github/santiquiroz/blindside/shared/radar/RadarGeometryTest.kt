package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarGeometryTest {
    private val origin = PointPx(240f, 278f)
    private val radius = 200f
    private val noShift = PointPx(0f, 0f)

    private fun assertPoint(expected: PointPx, actual: PointPx) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }

    private fun blip(id: Int, bearing: Double, range: Double, confidence: Confidence = Confidence.BOTH, ageMs: Long = 0, outOfView: Boolean = false) =
        Blip(displayId = id, bearingDeg = bearing, rangeM = range, confidence = confidence, ageMs = ageMs, outOfView = outOfView)

    private fun scene(blips: List<Blip>, linkUp: Boolean = true, eliminated: Boolean = false) = RadarScene(
        blips = blips,
        coverage = listOf(CoverageSector(-100.0, 80.0)),
        linkUp = linkUp,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = eliminated,
    )

    @Test
    fun `zero bearing points straight up`() {
        assertPoint(PointPx(240f, 178f), polarToPx(origin, radius, 0.0, 3.0))
    }

    @Test
    fun `positive bearings turn clockwise to the right`() {
        assertPoint(PointPx(440f, 278f), polarToPx(origin, radius, 90.0, 6.0))
    }

    @Test
    fun `ranges beyond six metres sit on the edge`() {
        assertPoint(PointPx(40f, 278f), polarToPx(origin, radius, -90.0, 12.0))
    }

    @Test
    fun `a contact at 8_4 m is drawn on the rim with its range in whole metres`() {
        val model = toDrawModel(scene(listOf(blip(1, 30.0, 8.4))), 480f, 480f, noShift, showContacts = true)
        val drawn = model.blips.single()

        assertPoint(polarToPx(model.origin, model.radiusPx, 30.0, DISPLAY_RANGE_M), drawn.center)
        assertEquals("8", drawn.farLabel)
    }

    @Test
    fun `a contact inside the display range has no far label`() {
        val model = toDrawModel(scene(listOf(blip(1, 30.0, 5.9))), 480f, 480f, noShift, showContacts = true)

        assertNull(model.blips.single().farLabel)
    }

    @Test
    fun `the far label rounds to the nearest metre and starts past the display edge`() {
        assertEquals("9", farLabel(8.6))
        assertEquals("10", farLabel(9.8))
        assertNull(farLabel(DISPLAY_RANGE_M))
    }

    @Test
    fun `nudging moves a point the given pixels toward a target`() {
        assertPoint(PointPx(100f, 10f), nudgeToward(PointPx(100f, 0f), PointPx(100f, 100f), 10f))
        assertPoint(PointPx(5f, 5f), nudgeToward(PointPx(5f, 5f), PointPx(5f, 5f), 10f))
    }

    @Test
    fun `the range scale marks two four and six metres up the front axis`() {
        val marks = rangeMarks(origin, radius)
        assertEquals(listOf(2, 4, 6), marks.map { it.meters })
        assertPoint(PointPx(240f, 278f - 200f / 3f), marks[0].at)
        assertPoint(PointPx(240f, 278f - 400f / 3f), marks[1].at)
        assertPoint(PointPx(240f, 78f), marks[2].at)
    }

    @Test
    fun `the right-handed coverage becomes a canvas arc`() {
        assertEquals(SectorDraw(-190f, 180f), sectorArc(CoverageSector(-100.0, 80.0)))
    }

    @Test
    fun `fill encodes confidence`() {
        assertEquals(BlipStyle.FILLED, blipStyle(Confidence.BOTH))
        assertEquals(BlipStyle.OUTLINE, blipStyle(Confidence.SINGLE))
        assertEquals(BlipStyle.DASHED, blipStyle(Confidence.COASTING))
    }

    @Test
    fun `opacity fades with age down to a floor`() {
        assertEquals(1f, blipAlpha(0L), 1e-6f)
        assertEquals(0.5f, blipAlpha(3_000L), 1e-6f)
        assertEquals(MIN_BLIP_ALPHA, blipAlpha(9_000L), 1e-6f)
    }

    @Test
    fun `the right handed half disc fan is centred and rings mark two and four metres`() {
        val model = toDrawModel(scene(emptyList()), 480f, 480f, noShift, showContacts = true, edgeMarginPx = 32f)
        assertPoint(PointPx(240f, 240f), model.origin)
        assertEquals(208f, model.radiusPx, 1e-3f)
        assertEquals(2, model.ringRadiiPx.size)
        assertEquals(model.radiusPx / 3f, model.ringRadiiPx[0], 1e-3f)
        assertEquals(model.radiusPx * 2f / 3f, model.ringRadiiPx[1], 1e-3f)
    }

    @Test
    fun `a narrower configured belt drops the origin below the centre`() {
        val model = toDrawModel(scene(emptyList()), 480f, 480f, noShift, showContacts = true, fitHalfAngleDeg = 60.0)
        assertTrue(model.origin.y > 240f)
    }

    @Test
    fun `the live coverage never sizes the fan`() {
        val narrowLive = scene(emptyList()).copy(coverage = listOf(CoverageSector(-60.0, 60.0)))
        val model = toDrawModel(narrowLive, 480f, 480f, noShift, showContacts = true)
        assertPoint(PointPx(240f, 240f), model.origin)
    }

    @Test
    fun `live contacts are drawn with their style`() {
        val model = toDrawModel(scene(listOf(blip(1, 0.0, 3.0, Confidence.SINGLE))), 480f, 480f, noShift, showContacts = true)
        assertEquals(BlipStyle.OUTLINE, model.blips.single().style)
        assertFalse(model.dimmed)
    }

    @Test
    fun `hidden contacts leave the fan dimmed but drawn`() {
        val model = toDrawModel(scene(listOf(blip(1, 0.0, 3.0))), 480f, 480f, noShift, showContacts = false)
        assertTrue(model.blips.isEmpty())
        assertTrue(model.dimmed)
        assertEquals(1, model.sectors.size)
    }

    @Test
    fun `out of view contacts become edge markers`() {
        val model = toDrawModel(scene(listOf(blip(2, 95.0, 4.0, outOfView = true))), 480f, 480f, noShift, showContacts = true)
        assertTrue(model.blips.isEmpty())
        assertEquals(1, model.edgeMarkers.size)
    }

    @Test
    fun `the burn-in offset moves everything`() {
        val still = toDrawModel(scene(emptyList()), 480f, 480f, noShift, showContacts = true)
        val shifted = toDrawModel(scene(emptyList()), 480f, 480f, PointPx(2f, -2f), showContacts = true)
        assertPoint(PointPx(still.origin.x + 2f, still.origin.y - 2f), shifted.origin)
    }

    @Test
    fun `contacts show only with a live link, in play and out of ambient`() {
        assertTrue(showContacts(scene(emptyList()), ambient = false))
        assertFalse(showContacts(null, ambient = false))
        assertFalse(showContacts(scene(emptyList(), linkUp = false), ambient = false))
        assertFalse(showContacts(scene(emptyList(), eliminated = true), ambient = false))
        assertFalse(showContacts(scene(emptyList()), ambient = true))
    }

    @Test
    fun `coasting contacts use the dim tone`() {
        assertEquals(ContactTone.FULL, contactTone(BlipStyle.FILLED))
        assertEquals(ContactTone.FULL, contactTone(BlipStyle.OUTLINE))
        assertEquals(ContactTone.DIM, contactTone(BlipStyle.DASHED))
    }
}
