package io.github.santiquiroz.blindside.core.scene

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.config.defaultMounts
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.tracking.CvKalman
import io.github.santiquiroz.blindside.core.tracking.Matrix
import io.github.santiquiroz.blindside.core.tracking.Track
import io.github.santiquiroz.blindside.core.tracking.TrackStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneBuilderTest {
    private val tracking = TrackingParams()
    private val decode = DecodeParams()
    private val mounts = defaultMounts(Handedness.RIGHT)
    private val bothAlive = listOf(SensorStatus(0, true), SensorStatus(1, true))

    @Test
    fun `a confirmed track is drawn at its predicted logical bearing`() {
        val track = track(Point2(0.0, 3.0), Point2(1.0, 0.0), TrackStatus.CONFIRMED, radars = setOf(0))

        val blip = buildScene(inputs(listOf(track), nowMs = 2_000), tracking, decode).blips.single()

        assertEquals(Math.toDegrees(Math.atan2(1.0, 3.0)), blip.bearingDeg, 0.5)
        assertEquals(Confidence.SINGLE, blip.confidence)
        assertEquals(1_000L, blip.ageMs)
    }

    @Test
    fun `yaw rotates the scene so a right turn moves contacts left`() {
        val track = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.CONFIRMED)

        val blip = buildScene(inputs(listOf(track), nowMs = 1_000, yawDeg = 30.0), tracking, decode).blips.single()

        assertEquals(-30.0, blip.bearingDeg, 1e-9)
    }

    @Test
    fun `tentative tracks are hidden and coasting ones are marked`() {
        val tentative = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.TENTATIVE)
        val coasting = track(Point2(1.0, 3.0), Point2.ZERO, TrackStatus.COASTING).copy(displayId = 2)
        val out = track(Point2(3.0, 0.5), Point2.ZERO, TrackStatus.OUT_OF_VIEW).copy(displayId = 3)

        val blips = buildScene(inputs(listOf(tentative, coasting, out), nowMs = 1_000), tracking, decode).blips

        assertEquals(listOf(2, 3), blips.map { it.displayId })
        assertEquals(Confidence.COASTING, blips[0].confidence)
        assertTrue(blips[1].outOfView)
    }

    @Test
    fun `both radars in the last two windows means BOTH confidence`() {
        val track = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.CONFIRMED, radars = setOf(0)).copy(previousWindowRadars = setOf(1))

        assertEquals(Confidence.BOTH, confidenceOf(track))
    }

    @Test
    fun `coverage lists one sector per alive radar`() {
        val scene = buildScene(inputs(emptyList(), 0).copy(radars = listOf(SensorStatus(0, true), SensorStatus(1, false))), tracking, decode)

        assertEquals(listOf(CoverageSector(-100.0, 20.0)), scene.coverage)
    }

    @Test
    fun `eliminated mode shows no contacts`() {
        val track = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.CONFIRMED)

        val scene = buildScene(inputs(listOf(track), 1_000).copy(eliminated = true), tracking, decode)

        assertTrue(scene.blips.isEmpty())
        assertTrue(scene.eliminated)
    }

    private fun inputs(tracks: List<Track>, nowMs: Long, yawDeg: Double = 0.0) = SceneInputs(
        nowMs = nowMs, tracks = tracks, yawDeg = yawDeg, mounts = mounts, radars = bothAlive, imus = bothAlive,
        motion = MotionState.STILL, warnings = emptySet(), linkUp = true, eliminated = false,
    )

    private fun track(position: Point2, velocity: Point2, status: TrackStatus, radars: Set<Int> = setOf(0, 1)): Track {
        val r = Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04)
        val kalman = CvKalman.init(position, r, 1.5).copy(x = Matrix.column(position.x, position.y, velocity.x, velocity.y))
        return Track(1, 1, kalman, stateMs = 1_000, bornMs = 500, lastHitMs = 1_000, status = status, windowRadars = radars)
    }
}
