package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleTest {
    private val params = TrackingParams()

    @Test
    fun `two evaluable misses in a row delete a tentative track`() {
        val once = track(TrackStatus.TENTATIVE, outcomes = listOf(WindowMark(900, true))).closeWindow(WindowOutcome.MISS, true, 1_100, params)!!

        assertNull(once.closeWindow(WindowOutcome.MISS, true, 1_200, params))
    }

    @Test
    fun `a tentative track silent for more than 1 s is deleted even without evaluable windows`() {
        assertNotNull(track(TrackStatus.TENTATIVE).closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_000, params))
        assertNull(track(TrackStatus.TENTATIVE).closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_100, params))
    }

    @Test
    fun `only evaluable windows are recorded with their start and at most five are kept`() {
        val full = track(TrackStatus.TENTATIVE, outcomes = List(5) { WindowMark(500L + it * 100, true) })

        val afterHit = full.closeWindow(WindowOutcome.HIT, true, 1_100, params)!!
        val afterSkip = full.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 1_100, params)!!

        assertEquals(5, afterHit.outcomes.size)
        assertEquals(WindowMark(1_000, true), afterHit.outcomes.last())
        assertEquals(full.outcomes, afterSkip.outcomes)
    }

    @Test
    fun `a confirmed track hit this window stays confirmed and rolls its radars`() {
        val closed = track(TrackStatus.CONFIRMED, windowRadars = setOf(0)).closeWindow(WindowOutcome.HIT, true, 1_100, params)!!

        assertEquals(TrackStatus.CONFIRMED, closed.status)
        assertEquals(setOf(0), closed.previousWindowRadars)
        assertTrue(closed.windowRadars.isEmpty())
    }

    @Test
    fun `a confirmed track without a hit starts coasting and is frozen if it was slow`() {
        val moving = track(TrackStatus.CONFIRMED, speed = 1.0).closeWindow(WindowOutcome.MISS, true, 1_100, params)!!
        val slow = track(TrackStatus.CONFIRMED, speed = 0.1).closeWindow(WindowOutcome.MISS, true, 1_100, params)!!

        assertEquals(TrackStatus.COASTING, moving.status)
        assertFalse(moving.lostStill)
        assertTrue(slow.lostStill)
        assertEquals(0.0, slow.kalman.speed, 0.0)
    }

    @Test
    fun `coasting lasts 1_5 s when lost moving and 6 s when lost still`() {
        val moving = track(TrackStatus.COASTING)
        val still = track(TrackStatus.COASTING).copy(lostStill = true)

        assertNotNull(moving.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_500, params))
        assertNull(moving.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_600, params))
        assertNotNull(still.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 7_000, params))
        assertNull(still.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 7_100, params))
    }

    @Test
    fun `leaving the cone coasts 0_3 s, then shows out of view for 5 s`() {
        val lost = track(TrackStatus.COASTING)

        assertEquals(TrackStatus.COASTING, lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 1_300, params)?.status)
        assertEquals(TrackStatus.OUT_OF_VIEW, lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 1_400, params)?.status)
        assertEquals(TrackStatus.OUT_OF_VIEW, lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 6_300, params)?.status)
        assertNull(lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 6_400, params))
    }

    @Test
    fun `a hit reacquires a lost track as confirmed`() {
        val back = track(TrackStatus.OUT_OF_VIEW).copy(lostStill = true).reacquired()

        assertEquals(TrackStatus.CONFIRMED, back.status)
        assertFalse(back.lostStill)
    }

    private fun track(
        status: TrackStatus,
        speed: Double = 1.0,
        outcomes: List<WindowMark> = emptyList(),
        windowRadars: Set<Int> = emptySet(),
    ): Track {
        val r = Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04)
        val kalman = CvKalman.init(Point2(0.0, 3.0), r, 1.5).copy(x = Matrix.column(0.0, 3.0, speed, 0.0))
        return Track(1, 1, kalman, stateMs = 1_000, bornMs = 500, lastHitMs = 1_000, status = status, outcomes = outcomes, windowRadars = windowRadars)
    }
}
