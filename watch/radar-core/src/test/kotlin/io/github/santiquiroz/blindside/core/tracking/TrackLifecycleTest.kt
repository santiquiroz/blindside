package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackLifecycleTest {
    private val ctx = testContext()

    @Test
    fun `three hits in consecutive windows confirm on the third hit`() {
        val run = replay(walkingTarget(1_000, 5, Point2(-1.0, 3.0), Point2(1.0, 0.0)), ctx)

        assertEquals(listOf(1_205L), run.confirmations.map { it.first })
        assertEquals(1, run.last.tracks.size)
        assertEquals(TrackStatus.CONFIRMED, run.last.tracks.single().status)
    }

    @Test
    fun `a tentative track is deleted after two evaluable misses`() {
        val frames = walkingTarget(1_000, 1, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + emptyFrames(1_100, 3)

        val run = replay(frames, ctx)

        assertTrue(run.last.tracks.isEmpty())
        assertTrue(run.confirmations.isEmpty())
    }

    @Test
    fun `saturated frames are not evaluable and do not delete a tentative track`() {
        val saturated = (0 until 3).map { k -> frameOf(0, 1_105 + k * 100L, emptyList(), occupiedSlots = 3) }

        val run = replay(walkingTarget(1_000, 1, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + saturated, ctx)

        assertEquals(1, run.last.tracks.size)
    }

    @Test
    fun `a tentative track silent for more than 1 s is dropped even when nothing is evaluable`() {
        val saturated = (0 until 15).map { k -> frameOf(0, 1_105 + k * 100L, emptyList(), occupiedSlots = 3) }

        val run = replay(walkingTarget(1_000, 1, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + saturated, ctx)

        assertEquals(1, run.states[1 + 9].tracks.size)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a confirmed track lost while moving coasts 1_5 s then disappears`() {
        val frames = walkingTarget(1_000, 4, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + emptyFrames(1_400, 20)

        val run = replay(frames, ctx)

        val at1_4s = run.states[4 + 14]
        assertEquals(TrackStatus.COASTING, at1_4s.tracks.single().status)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a confirmed track lost while still keeps its position for 6 s`() {
        val frames = walkingTarget(1_000, 6, Point2(0.0, 3.0), Point2(0.05, 0.0)) + emptyFrames(1_600, 64)

        val run = replay(frames, ctx)

        val at5_8s = run.states[6 + 58].tracks.single()
        assertTrue(at5_8s.lostStill)
        assertEquals(at5_8s.kalman.position, run.states[6 + 10].tracks.single().kalman.position)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a track leaving the cone becomes out of view, then is removed after 5_3 s`() {
        val frames = walkingTarget(1_000, 9, Point2(2.0, 2.0), Point2(1.5, 0.0)) + emptyFrames(1_900, 58)

        val run = replay(frames, ctx)

        assertEquals(TrackStatus.OUT_OF_VIEW, run.states[9 + 6].tracks.single().status)
        assertEquals(TrackStatus.OUT_OF_VIEW, run.states[9 + 50].tracks.single().status)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a hit while coasting reacquires the same track`() {
        val frames = walkingTarget(1_000, 4, Point2(-1.0, 3.0), Point2(1.0, 0.0)) +
            emptyFrames(1_400, 3) +
            listOf(frameOf(0, 1_705, listOf(Point2(-1.0 + 0.7 * 0.85, 3.0))))

        val run = replay(frames, ctx)

        val track = run.last.tracks.single()
        assertEquals(TrackStatus.CONFIRMED, track.status)
        assertEquals(1, run.confirmations.size)
    }

    @Test
    fun `a frame older than the last processed one is ignored`() {
        val run = replay(walkingTarget(1_000, 2, Point2(-1.0, 3.0), Point2(1.0, 0.0)), ctx)

        val step = Tracker.onFrame(run.last, frameOf(0, 1_050, listOf(Point2(3.0, 3.0))), ctx, TuningParams())

        assertEquals(run.last, step.state)
    }
}
