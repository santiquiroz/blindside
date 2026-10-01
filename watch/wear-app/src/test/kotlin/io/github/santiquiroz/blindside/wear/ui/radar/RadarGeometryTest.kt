package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `the origin sits below the centre and rings mark two and four metres`() {
        val model = toDrawModel(scene(emptyList()), 480f, 480f, noShift, showContacts = true)
        assertTrue(model.origin.y > 240f)
        assertEquals(2, model.ringRadiiPx.size)
        assertEquals(model.radiusPx / 3f, model.ringRadiiPx[0], 1e-3f)
        assertEquals(model.radiusPx * 2f / 3f, model.ringRadiiPx[1], 1e-3f)
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
}
