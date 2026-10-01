package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.MotionParams
import io.github.santiquiroz.blindside.core.scene.MotionState
import kotlin.math.abs

data class MotionDetector(
    val turnRateEmaDps: Double = 0.0,
    val watchTurnEmaDps: Double = 0.0,
    val lastWatchMs: Long? = null,
    val turnFromWatch: Boolean = false,
    val beltAccel: Map<Int, BeltAccel> = emptyMap(),
    val lastStepMs: Long? = null,
    val walkingHoldFromMs: Long? = null,
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

    fun withAccelNorm(imuId: Int, tMs: Long, normG: Double, params: MotionParams): MotionDetector {
        val updated = (beltAccel[imuId] ?: BeltAccel()).withNorm(tMs, normG, params)
        val stored = copy(beltAccel = beltAccel + (imuId to updated))
        return updated.lastStepMs?.let { stored.withStepEvent(it).withHoldFrom(it) } ?: stored
    }

    // Spec §6.6: watch steps arrive up to 2 s late, so their 1.2 s walking hold runs from when the step was heard.
    fun withWatchStep(eventMs: Long, heardMs: Long): MotionDetector = withStepEvent(eventMs).withHoldFrom(heardMs)

    fun withProne(isProne: Boolean): MotionDetector = copy(prone = isProne)

    fun isTurning(params: MotionParams): Boolean =
        (if (turnFromWatch) watchTurnEmaDps else turnRateEmaDps) > params.turningRateDps

    fun isWalking(tMs: Long, params: MotionParams): Boolean =
        beltAccel.values.any { it.spreadG(tMs, params) > params.walkingAccelStdG } || steppedRecently(tMs, params)

    fun isMoving(tMs: Long, params: MotionParams): Boolean = isWalking(tMs, params) || isTurning(params)

    fun state(tMs: Long, params: MotionParams): MotionState = when {
        prone -> MotionState.PRONE
        isWalking(tMs, params) -> MotionState.WALKING
        isTurning(params) -> MotionState.TURNING
        else -> MotionState.STILL
    }

    private fun withStepEvent(stepMs: Long): MotionDetector = copy(lastStepMs = maxOf(stepMs, lastStepMs ?: stepMs))

    private fun withHoldFrom(fromMs: Long): MotionDetector = copy(walkingHoldFromMs = maxOf(fromMs, walkingHoldFromMs ?: fromMs))

    private fun steppedRecently(tMs: Long, params: MotionParams): Boolean {
        val from = walkingHoldFromMs ?: return false
        return tMs - from <= params.stepHoldMs
    }
}

private fun smoothed(ema: Double, value: Double, dtS: Double, params: MotionParams): Double {
    val alpha = dtS / (params.turningTauS + dtS)
    return ema + (value - ema) * alpha
}
