package io.github.santiquiroz.blindside.core.geometry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class Point2(val x: Double, val y: Double) {
    operator fun plus(other: Point2) = Point2(x + other.x, y + other.y)
    operator fun minus(other: Point2) = Point2(x - other.x, y - other.y)
    operator fun times(factor: Double) = Point2(x * factor, y * factor)
    val norm: Double get() = hypot(x, y)

    // Bearing convention of the whole project: 0 = straight ahead (+y), positive = clockwise (to the right).
    val bearingDeg: Double get() = Math.toDegrees(atan2(x, y))

    fun rotateClockwise(angleDeg: Double): Point2 {
        val a = Math.toRadians(angleDeg)
        return Point2(x * cos(a) + y * sin(a), -x * sin(a) + y * cos(a))
    }

    companion object {
        val ZERO = Point2(0.0, 0.0)

        fun fromPolar(rangeM: Double, bearingDeg: Double): Point2 {
            val a = Math.toRadians(bearingDeg)
            return Point2(rangeM * sin(a), rangeM * cos(a))
        }
    }
}

fun wrapDeg(angleDeg: Double): Double {
    val wrapped = (angleDeg + 180.0) % 360.0
    return (if (wrapped < 0) wrapped + 360.0 else wrapped) - 180.0
}
