package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StopAndScanTest {
    private val movingUntil1500 = testContext(motion = { t -> MotionContext(moving = t < 1_500, gateOpenFromMs = 2_000) })

    @Test
    fun `while the player moves no new track is confirmed`() {
        val alwaysMoving = testContext(motion = { MotionContext(moving = true, gateOpenFromMs = Long.MAX_VALUE) })

        val run = replay(walkingTarget(1_000, 15, Point2(-2.0, 3.0), Point2(1.5, 0.0)), alwaysMoving)

        assertTrue(run.confirmations.isEmpty())
    }

    @Test
    fun `after the movement a new track needs three hits in windows after the 0_5 s tail`() {
        val run = replay(walkingTarget(1_000, 16, Point2(-2.0, 3.0), Point2(0.8, 0.0)), movingUntil1500)

        assertEquals(listOf(2_205L), run.confirmations.map { it.first })
    }

    @Test
    fun `a track confirmed before the movement keeps its id and is not confirmed again`() {
        val stillThenMoving = testContext(motion = { t -> MotionContext(moving = t >= 1_500, gateOpenFromMs = Long.MIN_VALUE) })

        val run = replay(walkingTarget(1_000, 12, Point2(-2.0, 3.0), Point2(0.8, 0.0)), stillThenMoving)

        assertEquals(listOf(1_205L), run.confirmations.map { it.first })
        assertEquals(TrackStatus.CONFIRMED, run.last.tracks.single().status)
        assertEquals(1, run.last.tracks.single().displayId)
    }

    @Test
    fun `a static reflector seen only while turning never confirms`() {
        val frames = (0 until 5).map { k -> frameOf(0, 1_005 + k * 100L, listOf(Point2(0.5, 3.0))) } + emptyFrames(1_500, 15)

        val run = replay(frames, movingUntil1500, TuningParams())

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.last.tracks.isEmpty())
    }
}
