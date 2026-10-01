package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams

enum class Stillness { VERIFIED, UNVERIFIED, MOVING }

// Spec §6.3: the belt boxes cannot tell a slow steady turn from a gyro offset; the watch gyro (drift-compensated by Android) can.
data class WatchWitness(val samples: List<TimedValue> = emptyList()) {
    fun withSample(tMs: Long, rateDps: Double, params: ImuParams): WatchWitness =
        copy(samples = (samples + TimedValue(tMs, rateDps)).filter { it.tMs >= tMs - params.witnessHistoryMs })

    fun isMoving(fromMs: Long, toMs: Long, params: ImuParams): Boolean =
        inside(fromMs, toMs).any { it.value > params.witnessMaxDps }

    fun hasCoverage(fromMs: Long, toMs: Long, params: ImuParams): Boolean =
        inside(fromMs, toMs).size >= params.witnessMinSamples

    private fun inside(fromMs: Long, toMs: Long) = samples.filter { it.tMs in fromMs..toMs }
}

data class RestEvidence(val watch: WatchWitness = WatchWitness(), val lastStepMs: Long? = null) {
    fun judge(fromMs: Long, toMs: Long, params: ImuParams): Stillness = when {
        steppedSince(fromMs) || watch.isMoving(fromMs, toMs, params) -> Stillness.MOVING
        watch.hasCoverage(fromMs, toMs, params) -> Stillness.VERIFIED
        else -> Stillness.UNVERIFIED
    }

    private fun steppedSince(fromMs: Long): Boolean = lastStepMs != null && lastStepMs >= fromMs
}
