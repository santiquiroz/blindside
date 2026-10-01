package io.github.santiquiroz.blindside.shared.compass

import io.github.santiquiroz.blindside.shared.radar.PointPx
import kotlin.math.cos
import kotlin.math.sin

data class CompassTick(val angleDeg: Float, val major: Boolean)

data class CardinalMark(val label: String, val angleDeg: Float)

private const val TICK_STEP_DEG = 15
private const val LETTER_STEP_DEG = 90
private const val MAJOR_STEP_DEG = 45

val CARDINAL_MARKS = listOf(CardinalMark("N", 0f), CardinalMark("E", 90f), CardinalMark("S", 180f), CardinalMark("O", 270f))

fun compassTicks(): List<CompassTick> =
    (0 until 360 step TICK_STEP_DEG)
        .filter { it % LETTER_STEP_DEG != 0 }
        .map { CompassTick(it.toFloat(), major = it % MAJOR_STEP_DEG == 0) }

// The ring turns against the watch azimuth, so on the glass each mark points at its real direction in every posture.
fun markScreenAngleDeg(markAngleDeg: Float, azimuthDeg: Double): Float =
    normalizedDeg(markAngleDeg - azimuthDeg).toFloat()

fun pointOnRing(center: PointPx, radiusPx: Float, angleDeg: Float): PointPx {
    val radians = Math.toRadians(angleDeg.toDouble())
    return PointPx(center.x + (radiusPx * sin(radians)).toFloat(), center.y - (radiusPx * cos(radians)).toFloat())
}
