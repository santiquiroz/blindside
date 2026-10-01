package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import kotlin.math.exp

data class YawPoint(val tMs: Long, val yawDeg: Double)

data class YawTracker(
    val history: List<YawPoint> = emptyList(),
    val recentRates: List<Double> = emptyList(),
    val blendOffsetDeg: Double = 0.0,
    val blendAnchorMs: Long = 0,
) {
    val lastPoint: YawPoint? get() = history.lastOrNull()

    fun apply(increments: List<YawIncrement>, params: ImuParams): YawTracker {
        val previous = lastPoint
        val fresh = increments.filter { previous == null || it.tEndMs > previous.tMs }.sortedBy { it.tEndMs }
        if (fresh.isEmpty()) return this
        val start = previous ?: YawPoint(fresh.first().tEndMs - fresh.first().durationMs, 0.0)
        val added = fresh.runningFold(start) { acc, inc -> YawPoint(inc.tEndMs, acc.yawDeg + inc.deltaDeg) }.drop(1)
        val newest = added.last()
        val merged = (history.ifEmpty { listOf(start) } + added).filter { it.tMs >= newest.tMs - params.yawHistoryMs }
        val rates = (recentRates + fresh.filter { it.durationMs == IMU_SAMPLE_PERIOD_MS }.map { it.rateDps })
            .takeLast(params.yawRateSamplesForExtrapolation)
        val offset = if (history.isEmpty()) 0.0 else displayYawAt(newest.tMs, params) - newest.yawDeg
        return YawTracker(merged, rates, offset, newest.tMs)
    }

    fun yawAt(tMs: Long, params: ImuParams): Double {
        val last = lastPoint ?: return 0.0
        if (tMs >= last.tMs) return last.yawDeg + extrapolationRate() * minOf(tMs - last.tMs, params.yawExtrapolationMaxMs) / 1000.0
        if (tMs <= history.first().tMs) return history.first().yawDeg
        return interpolate(tMs)
    }

    fun displayYawAt(tMs: Long, params: ImuParams): Double {
        val elapsed = (tMs - blendAnchorMs).coerceAtLeast(0)
        return yawAt(tMs, params) + blendOffsetDeg * exp(-elapsed / params.yawBlendTauMs)
    }

    fun extrapolationRate(): Double = if (recentRates.isEmpty()) 0.0 else recentRates.average()

    private fun interpolate(tMs: Long): Double {
        val index = history.indexOfFirst { it.tMs >= tMs }
        val after = history[index]
        val before = history[index - 1]
        val fraction = (tMs - before.tMs).toDouble() / (after.tMs - before.tMs)
        return before.yawDeg + (after.yawDeg - before.yawDeg) * fraction
    }
}
