package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.MotionParams

// One per box IMU: two chips can read |a| up to ~0.2 g apart (zero-g offset and sensitivity), so their series never mix.
data class BeltAccel(
    val norms: List<TimedValue> = emptyList(),
    val stepArmed: Boolean = true,
    val lastStepMs: Long? = null,
) {
    fun withNorm(tMs: Long, normG: Double, params: MotionParams): BeltAccel {
        val window = (norms + TimedValue(tMs, normG)).filter { it.tMs > tMs - params.walkingWindowMs }
        return copy(norms = window).withStepCheck(tMs, normG - window.meanValue(), params)
    }

    fun spreadG(): Double = norms.map { it.value }.standardDeviation()

    // The excess is measured from the chip's own 1 s mean |a|, not from 1 g, so a constant offset is never a step.
    private fun withStepCheck(tMs: Long, excessG: Double, params: MotionParams): BeltAccel {
        if (!stepArmed) return if (excessG < params.stepResetG) copy(stepArmed = true) else this
        return if (excessG > params.stepRiseG && isFarFromLastStep(tMs, params)) copy(stepArmed = false, lastStepMs = tMs) else this
    }

    private fun isFarFromLastStep(tMs: Long, params: MotionParams): Boolean =
        lastStepMs == null || tMs - lastStepMs >= params.stepMinIntervalMs
}

private fun List<TimedValue>.meanValue(): Double = map { it.value }.average()
