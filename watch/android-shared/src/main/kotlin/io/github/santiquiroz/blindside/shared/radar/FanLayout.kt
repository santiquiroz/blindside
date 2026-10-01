package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.coverageOf
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.toPipelineConfig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class FanFit(val originYOffsetPx: Float, val radiusPx: Float)

const val MIN_FIT_HALF_ANGLE_DEG = 45.0
const val MAX_FIT_HALF_ANGLE_DEG = 90.0

fun fanHalfAngleDeg(sectors: List<CoverageSector>): Double =
    (sectors.flatMap { listOf(abs(it.fromDeg), abs(it.toDeg)) }.maxOrNull() ?: MAX_FIT_HALF_ANGLE_DEG)
        .coerceIn(MIN_FIT_HALF_ANGLE_DEG, MAX_FIT_HALF_ANGLE_DEG)

// Spec §6: the fan is sized from the belt as configured, not from the radars alive now, so a flapping radar never resizes it mid-game.
fun configuredFanHalfAngleDeg(config: PipelineConfig): Double =
    fanHalfAngleDeg(coverageOf(config.mounts, config.mounts.map { it.radarId }.toSet(), config.tuning.decode))

fun fanHalfAngleFor(settings: AppSettings): Double = configuredFanHalfAngleDeg(toPipelineConfig(settings))

// Largest fan on a round screen: dropping the origin by L·cot(A) lets the flank edge and the 6 m arc both touch the usable circle.
fun fitFan(sidePx: Float, edgeMarginPx: Float, halfAngleDeg: Double): FanFit {
    val limit = (sidePx / 2f - edgeMarginPx).coerceAtLeast(0f).toDouble()
    val radians = Math.toRadians(halfAngleDeg.coerceIn(MIN_FIT_HALF_ANGLE_DEG, MAX_FIT_HALF_ANGLE_DEG))
    return FanFit(
        originYOffsetPx = (limit * cos(radians) / sin(radians)).toFloat(),
        radiusPx = (limit / sin(radians)).toFloat(),
    )
}
