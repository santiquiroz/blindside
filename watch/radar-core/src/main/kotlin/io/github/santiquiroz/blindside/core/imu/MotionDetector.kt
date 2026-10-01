package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.MotionParams
import io.github.santiquiroz.blindside.core.scene.MotionState
import kotlin.math.abs

data class MotionDetector(
    val turnRateEmaDps: Double = 0.0,
    val watchTurnEmaDps: Double = 0.0,
    val lastWatchMs: Long? = null,
    val turnFromWatch: Boolean = false,
    val accelNorms: List<TimedValue> = emptyList(),
    val stepArmed: Boolean = true,
    val lastStepMs: Long? = null,
    val prone: Boolean = false,
) {
    fun withYawIncrement(increment: YawIncrement, params: MotionParams): MotionDetector =
        copy(turnRateEmaDps = smoothed(turnRateEmaDps, abs(increment.rateDps), increment.durationMs / 1000.0, params))

    fun withWatchRate(tMs: Long, rateDps: Double, params: MotionParams): MotionDetector {
        val gapMs = lastWatchMs?.let { (tMs - it).coerceIn(0, params.watchMaxGapMs) } ?: 0L
        return copy(watchTurnEmaDps = smoothed(watchTurnEmaDps, rateDps, gapMs / 1000.0, params), lastWatchMs = tMs)
    }

    // Spec §6.6: with both box IMUs down, "girando" comes from the watch gyroscope.
    fun withTurnSource(fromWatch: Boolean): MotionDetector = copy(turnFromWatch = fromWatch)

    fun withAccelNorm(tMs: Long, normG: Double, params: MotionParams): MotionDetector {
        val window = (accelNorms + TimedValue(tMs, normG)).filter { it.tMs > tMs - params.walkingWindowMs }
        return copy(accelNorms = window).withBeltStep(tMs, normG, params)
    }

    fun withStep(tMs: Long): MotionDetector = copy(lastStepMs = maxOf(tMs, lastStepMs ?: tMs))

    fun withProne(isProne: Boolean): MotionDetector = copy(prone = isProne)

    fun isTurning(params: MotionParams): Boolean =
        (if (turnFromWatch) watchTurnEmaDps else turnRateEmaDps) > params.turningRateDps

    fun isWalking(tMs: Long, params: MotionParams): Boolean =
        accelSpreadG() > params.walkingAccelStdG || steppedRecently(tMs, params)

    fun isMoving(tMs: Long, params: MotionParams): Boolean = isWalking(tMs, params) || isTurning(params)

    fun state(tMs: Long, params: MotionParams): MotionState = when {
        prone -> MotionState.PRONE
        isWalking(tMs, params) -> MotionState.WALKING
        isTurning(params) -> MotionState.TURNING
        else -> MotionState.STILL
    }

    private fun accelSpreadG(): Double = accelNorms.map { it.value }.standardDeviation()

    private fun steppedRecently(tMs: Long, params: MotionParams): Boolean {
        val last = lastStepMs ?: return false
        return tMs - last <= params.stepHoldMs
    }

    private fun withBeltStep(tMs: Long, normG: Double, params: MotionParams): MotionDetector {
        val excess = normG - 1.0
        if (!stepArmed) return if (excess < params.stepResetG) copy(stepArmed = true) else this
        val farEnough = lastStepMs == null || tMs - lastStepMs >= params.stepMinIntervalMs
        return if (excess > params.stepRiseG && farEnough) copy(stepArmed = false, lastStepMs = tMs) else this
    }
}

private fun smoothed(ema: Double, value: Double, dtS: Double, params: MotionParams): Double {
    val alpha = dtS / (params.turningTauS + dtS)
    return ema + (value - ema) * alpha
}
