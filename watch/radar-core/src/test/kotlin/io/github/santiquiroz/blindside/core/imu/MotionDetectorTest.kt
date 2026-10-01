package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.MotionParams
import io.github.santiquiroz.blindside.core.scene.MotionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class MotionDetectorTest {
    private val params = MotionParams()

    @Test
    fun `a sustained 60 deg per s turn is turning, a 10 deg per s sway is not`() {
        val turning = (1..20).fold(MotionDetector()) { m, i -> m.withYawIncrement(YawIncrement(i * 20L, 20, 1.2), params) }
        val swaying = (1..20).fold(MotionDetector()) { m, i -> m.withYawIncrement(YawIncrement(i * 20L, 20, 0.2), params) }

        assertEquals(MotionState.TURNING, turning.state(400, params))
        assertEquals(MotionState.STILL, swaying.state(400, params))
    }

    @Test
    fun `turning is filtered so a single spike does not count`() {
        val spike = MotionDetector().withYawIncrement(YawIncrement(20, 20, 1.2), params)

        assertFalse(spike.isTurning(params))
    }

    @Test
    fun `walking bob of 0_25 g is walking and still accel is not`() {
        val walking = walk(MotionDetector(), 0 until 50, amplitudeG = 0.25)
        val still = walk(MotionDetector(), 0 until 50, amplitudeG = 0.0)

        assertEquals(MotionState.WALKING, walking.state(980, params))
        assertEquals(MotionState.STILL, still.state(980, params))
    }

    @Test
    fun `the belt step detector keeps walking for 1_2 s after the last step`() {
        val walked = walk(MotionDetector(), 0 until 50, amplitudeG = 0.25)
        val stopped = walk(walked, 50 until 150, amplitudeG = 0.0)

        assertTrue(walked.lastStepMs != null)
        assertTrue(stopped.isWalking(walked.lastStepMs!! + 1_100, params))
        assertFalse(stopped.isWalking(walked.lastStepMs!! + 1_300, params))
    }

    @Test
    fun `two still box imus whose |a| differs by 0_15 g are not walking`() {
        val pair = (0 until 100).fold(MotionDetector()) { m, i ->
            m.withAccelNorm(0, i * 20L, 1.0, params).withAccelNorm(1, i * 20L + 7, 1.15, params)
        }

        assertEquals(MotionState.STILL, pair.state(1_990, params))
        assertEquals(null, pair.lastStepMs)
    }

    @Test
    fun `a constant zero-g offset above the step rise is not a step`() {
        val offset = walk(MotionDetector(), 0 until 100, amplitudeG = 0.0, offsetG = 0.3)

        assertEquals(null, offset.lastStepMs)
        assertEquals(MotionState.STILL, offset.state(1_980, params))
    }

    @Test
    fun `a watch step alone keeps walking for 1_2 s`() {
        val stepped = MotionDetector().withStep(5_000)

        assertTrue(stepped.isWalking(6_000, params))
        assertFalse(stepped.isWalking(6_300, params))
    }

    @Test
    fun `with the box imus down a 30 deg per s watch rotation is turning`() {
        val watch = (1..10).fold(MotionDetector().withTurnSource(fromWatch = true)) { m, i -> m.withWatchRate(i * 100L, 30.0, params) }
        val belt = (1..10).fold(MotionDetector()) { m, i -> m.withWatchRate(i * 100L, 30.0, params) }

        assertEquals(MotionState.TURNING, watch.state(1_000, params))
        assertEquals(MotionState.STILL, belt.state(1_000, params))
    }

    @Test
    fun `a single watch spike is filtered out`() {
        val spike = MotionDetector().withTurnSource(fromWatch = true).withWatchRate(100, 30.0, params).withWatchRate(200, 0.0, params)

        assertFalse(spike.isTurning(params))
    }

    @Test
    fun `moving means walking or turning`() {
        val stepped = MotionDetector().withStep(5_000)

        assertTrue(stepped.isMoving(5_500, params))
        assertFalse(stepped.isMoving(7_000, params))
    }

    @Test
    fun `prone wins over every other state`() {
        val prone = walk(MotionDetector(), 0 until 50, amplitudeG = 0.25).withProne(true)

        assertEquals(MotionState.PRONE, prone.state(980, params))
    }

    private fun walk(start: MotionDetector, samples: IntRange, amplitudeG: Double, offsetG: Double = 0.0): MotionDetector =
        samples.fold(start) { m, i ->
            val tMs = i * 20L
            m.withAccelNorm(0, tMs, 1.0 + offsetG + amplitudeG * sin(2 * PI * 2.0 * tMs / 1000.0), params)
        }
}
