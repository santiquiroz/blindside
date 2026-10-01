package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfirmationPolicyTest {
    private val params = TrackingParams()
    private val threeOfFour = listOf(WindowMark(1_000, true), WindowMark(1_100, false), WindowMark(1_200, true), WindowMark(1_300, true))

    @Test
    fun `three hits in the last five evaluable windows confirm when still`() {
        assertTrue(canConfirm(track(), threeOfFour, MotionContext.STILL, params))
        assertFalse(canConfirm(track(), threeOfFour.drop(1), MotionContext.STILL, params))
    }

    @Test
    fun `nothing is promoted while the player moves`() {
        assertFalse(canConfirm(track(), threeOfFour, MotionContext(moving = true, gateOpenFromMs = Long.MIN_VALUE), params))
    }

    @Test
    fun `windows that started before the end of the tail do not count`() {
        val gate = MotionContext(moving = false, gateOpenFromMs = 1_100)

        assertEquals(2, hitsAfterGate(threeOfFour, gate.gateOpenFromMs, params))
        assertFalse(canConfirm(track(), threeOfFour, gate, params))
        assertTrue(canConfirm(track(), threeOfFour + WindowMark(1_400, true), gate, params))
    }

    @Test
    fun `a track that is already confirmed is not confirmed again`() {
        assertFalse(canConfirm(track().copy(status = TrackStatus.CONFIRMED), threeOfFour, MotionContext.STILL, params))
    }

    @Test
    fun `window outcome follows hits, evidence and coverage`() {
        val hit = track().copy(windowRadars = setOf(0))

        assertEquals(WindowOutcome.HIT, windowOutcome(hit, setOf(0), setOf(0)))
        assertEquals(WindowOutcome.MISS, windowOutcome(track(), setOf(0), setOf(0)))
        assertEquals(WindowOutcome.NOT_EVALUABLE, windowOutcome(track(), setOf(1), setOf(0)))
        assertEquals(WindowOutcome.NOT_EVALUABLE, windowOutcome(track(), emptySet(), setOf(0)))
    }

    private fun track(): Track {
        val r = Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04)
        return Track(1, 1, CvKalman.init(Point2(0.0, 3.0), r, 1.5), stateMs = 1_000, bornMs = 1_000, lastHitMs = 1_000)
    }
}
