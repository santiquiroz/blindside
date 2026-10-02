package io.github.santiquiroz.blindside.shared.sensors

import kotlin.math.acos
import kotlin.math.sqrt

data class GravityTemplate(val x: Float, val y: Float, val z: Float, val rotationDeg: Float)

const val MIN_GRAVITY_MAGNITUDE = 3f
const val TACTICAL_LEFT_ROTATION_DEG = 90f
const val TACTICAL_RIGHT_ROTATION_DEG = -90f

// Holding the replica for 3 s averages to a steady gravity vector; moving the whole time averages toward zero and is rejected.
fun captureGravityTemplate(samples: List<Vec3>): GravityTemplate? {
    if (samples.isEmpty()) return null
    val mean = Vec3(
        samples.sumOf { it.x.toDouble() }.toFloat() / samples.size,
        samples.sumOf { it.y.toDouble() }.toFloat() / samples.size,
        samples.sumOf { it.z.toDouble() }.toFloat() / samples.size,
    )
    if (magnitude(mean) < MIN_GRAVITY_MAGNITUDE) return null
    return GravityTemplate(mean.x, mean.y, mean.z, tacticalRotationDeg(mean))
}

// The grip rolls the watch; the sign of the x-gravity says which wrist it rolled toward, so which way to turn the radar.
private fun tacticalRotationDeg(g: Vec3): Float = if (g.x >= 0f) TACTICAL_LEFT_ROTATION_DEG else TACTICAL_RIGHT_ROTATION_DEG

fun angleBetweenDeg(a: Vec3, b: Vec3): Double {
    val mags = magnitude(a).toDouble() * magnitude(b).toDouble()
    if (mags <= 1e-6) return 180.0
    val cosine = ((a.x * b.x + a.y * b.y + a.z * b.z).toDouble() / mags).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

private fun magnitude(v: Vec3): Float = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
