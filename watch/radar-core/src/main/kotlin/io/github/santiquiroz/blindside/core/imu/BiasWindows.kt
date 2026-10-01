package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams

data class WindowStats(val mean: Vec3, val spreadDps: Double, val accelStdG: Double)

sealed interface BootVerdict {
    data class Accepted(val bias: Vec3, val verified: Boolean) : BootVerdict
    data object Moving : BootVerdict
    data object Defective : BootVerdict
}

sealed interface RestVerdict {
    data object NotResting : RestVerdict
    data class Refine(val mean: Vec3, val verified: Boolean) : RestVerdict
    data class Reseed(val mean: Vec3) : RestVerdict
    data object Unverified : RestVerdict
}

fun windowStats(window: List<ImuReading>): WindowStats {
    val mean = window.map { it.gyroDps }.mean()
    return WindowStats(
        mean = mean,
        spreadDps = window.maxOf { (it.gyroDps - mean).maxAbs() },
        accelStdG = window.map { it.accelG.norm }.standardDeviation(),
    )
}

// Spec §6.3: stillness is judged by spread, not magnitude, because the raw offset reaches ±20 °/s.
fun isStillWindow(stats: WindowStats, params: ImuParams): Boolean =
    stats.spreadDps <= params.stillMaxSpreadDps && stats.accelStdG <= params.stillMaxAccelStdG

fun evaluateBootWindow(window: List<ImuReading>, stillness: Stillness, params: ImuParams): BootVerdict {
    val stats = windowStats(window)
    if (stillness == Stillness.MOVING || !isStillWindow(stats, params)) return BootVerdict.Moving
    if (stats.mean.maxAbs() > params.defectiveMeanDps) return BootVerdict.Defective
    return BootVerdict.Accepted(stats.mean, verified = stillness == Stillness.VERIFIED)
}

// Spec §6.3: a still window whose mean is 3-45 °/s away from b means b absorbed a turn; only the watch can confirm it.
fun evaluateRestWindow(window: List<ImuReading>, bias: Vec3, stillness: Stillness, params: ImuParams): RestVerdict {
    val stats = windowStats(window)
    if (stillness == Stillness.MOVING || !isStillWindow(stats, params)) return RestVerdict.NotResting
    val offset = (stats.mean - bias).maxAbs()
    return when {
        offset < params.reseedMinOffsetDps -> RestVerdict.Refine(stats.mean, verified = stillness == Stillness.VERIFIED)
        offset >= params.defectiveMeanDps -> RestVerdict.NotResting
        stillness == Stillness.VERIFIED -> RestVerdict.Reseed(stats.mean)
        else -> RestVerdict.Unverified
    }
}
