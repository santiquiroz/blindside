package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.wear.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DeviceRotationTest {
    private val widthPx = 480f
    private val heightPx = 480f
    private val noShift = PointPx(0f, 0f)
    private val center = screenCenter(widthPx, heightPx, noShift)

    private fun assertPoint(expected: PointPx, actual: PointPx) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }

    private fun blip(id: Int, bearing: Double, range: Double, outOfView: Boolean = false) =
        Blip(displayId = id, bearingDeg = bearing, rangeM = range, confidence = Confidence.SINGLE, ageMs = 1_500, outOfView = outOfView)

    private fun scene(blips: List<Blip>) = RadarScene(
        blips = blips,
        coverage = listOf(CoverageSector(-100.0, 80.0)),
        linkUp = true,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )

    private fun logicalModel(vararg blips: Blip, offset: PointPx = noShift): RadarDrawModel =
        toDrawModel(scene(blips.toList()), widthPx, heightPx, offset, showContacts = true)

    private fun distancePx(rangeM: Double, model: RadarDrawModel): Float = (rangeM / MAX_RANGE_M).toFloat() * model.radiusPx

    @Test
    fun `a clockwise quarter turn sends a point above the pivot to its right`() {
        assertPoint(PointPx(110f, 100f), rotatePoint(PointPx(100f, 90f), PointPx(100f, 100f), 90f))
        assertPoint(PointPx(-50f, 100f), rotatePoint(PointPx(100f, -50f), PointPx(100f, 100f), -90f))
    }

    @Test
    fun `the normal posture leaves the drawing untouched`() {
        val model = logicalModel(blip(1, 30.0, 3.0), blip(2, 95.0, 4.0, outOfView = true))
        assertSame(model, model.rotatedAbout(center, WatchPosture.NORMAL.rotationDeg))
    }

    @Test
    fun `tactical left turns the drawing's front to three o'clock`() {
        val rotated = logicalModel(blip(1, 0.0, 3.0)).rotatedAbout(center, WatchPosture.TACTICAL_LEFT.rotationDeg)
        val ahead = rotated.blips.single().center
        assertPoint(PointPx(rotated.origin.x + distancePx(3.0, rotated), rotated.origin.y), ahead)
        assertEquals(center.y, rotated.origin.y, 1e-3f)
    }

    @Test
    fun `tactical right turns the drawing's front to nine o'clock`() {
        val rotated = logicalModel(blip(1, 0.0, 3.0)).rotatedAbout(center, WatchPosture.TACTICAL_RIGHT.rotationDeg)
        val ahead = rotated.blips.single().center
        assertPoint(PointPx(rotated.origin.x - distancePx(3.0, rotated), rotated.origin.y), ahead)
        assertEquals(center.y, rotated.origin.y, 1e-3f)
    }

    @Test
    fun `a contact on the body's right is drawn at six o'clock in tactical left`() {
        val rotated = logicalModel(blip(1, 90.0, 6.0)).rotatedAbout(center, WatchPosture.TACTICAL_LEFT.rotationDeg)
        assertPoint(PointPx(rotated.origin.x, rotated.origin.y + rotated.radiusPx), rotated.blips.single().center)
    }

    @Test
    fun `the fan arcs turn with the posture`() {
        val logical = logicalModel()
        assertEquals(SectorDraw(-100f, 180f), logical.rotatedAbout(center, WatchPosture.TACTICAL_LEFT.rotationDeg).sectors.single())
        assertEquals(SectorDraw(-280f, 180f), logical.rotatedAbout(center, WatchPosture.TACTICAL_RIGHT.rotationDeg).sectors.single())
    }

    @Test
    fun `edge markers turn with the rest of the drawing`() {
        val logical = logicalModel(blip(2, 95.0, 4.0, outOfView = true))
        val marker = logical.edgeMarkers.single()
        val rotated = logical.rotatedAbout(center, WatchPosture.TACTICAL_LEFT.rotationDeg).edgeMarkers.single()
        assertPoint(rotatePoint(marker.inner, center, 90f), rotated.inner)
        assertPoint(rotatePoint(marker.outer, center, 90f), rotated.outer)
        assertEquals(marker.alpha, rotated.alpha)
    }

    @Test
    fun `turning keeps every size, style and fade`() {
        val logical = logicalModel(blip(1, 30.0, 3.0))
        val rotated = logical.rotatedAbout(center, WatchPosture.TACTICAL_RIGHT.rotationDeg)
        assertEquals(logical.radiusPx, rotated.radiusPx)
        assertEquals(logical.blipRadiusPx, rotated.blipRadiusPx)
        assertEquals(logical.ringRadiiPx, rotated.ringRadiiPx)
        assertEquals(logical.dimmed, rotated.dimmed)
        assertEquals(logical.blips.map { it.style to it.alpha }, rotated.blips.map { it.style to it.alpha })
    }

    @Test
    fun `the burn-in shift stays a plain translation after turning`() {
        val shift = PointPx(3f, -2f)
        val still = logicalModel().rotatedAbout(center, WatchPosture.TACTICAL_LEFT.rotationDeg)
        val shifted = logicalModel(offset = shift)
            .rotatedAbout(screenCenter(widthPx, heightPx, shift), WatchPosture.TACTICAL_LEFT.rotationDeg)
        assertPoint(PointPx(still.origin.x + shift.x, still.origin.y + shift.y), shifted.origin)
    }
}
