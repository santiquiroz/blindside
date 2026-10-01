package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.CoverageSector
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class FanFit(val originYOffsetPx: Float, val radiusPx: Float)

const val MIN_FIT_HALF_ANGLE_DEG = 45.0
const val MAX_FIT_HALF_ANGLE_DEG = 90.0

fun fanHalfAngleDeg(sectors: List<CoverageSector>): Double =
    (sectors.flatMap { listOf(abs(it.fromDeg), abs(it.toDeg)) }.maxOrNull() ?: MAX_FIT_HALF_ANGLE_DEG)
        .coerceIn(MIN_FIT_HALF_ANGLE_DEG, MAX_FIT_HALF_ANGLE_DEG)

// Largest fan on a round screen: dropping the origin by L·cot(A) lets the flank edge and the 6 m arc both touch the usable circle.
fun fitFan(sidePx: Float, edgeMarginPx: Float, halfAngleDeg: Double): FanFit {
    val limit = (sidePx / 2f - edgeMarginPx).coerceAtLeast(0f).toDouble()
    val radians = Math.toRadians(halfAngleDeg.coerceIn(MIN_FIT_HALF_ANGLE_DEG, MAX_FIT_HALF_ANGLE_DEG))
    return FanFit(
        originYOffsetPx = (limit * cos(radians) / sin(radians)).toFloat(),
        radiusPx = (limit / sin(radians)).toFloat(),
    )
}
