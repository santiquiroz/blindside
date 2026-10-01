package io.github.santiquiroz.blindside.wear.sensors

data class Vec3(val x: Float, val y: Float, val z: Float)

enum class GravitySource { GRAVITY_SENSOR, ACCELEROMETER_LOW_PASS, NONE }

const val GRAVITY_MIN_INTERVAL_NANOS = 100_000_000L
const val GYRO_MIN_INTERVAL_NANOS = 100_000_000L
const val ACCEL_LOW_PASS_TAU_NANOS = 300_000_000L

fun <T : Any> preferWakeUp(wakeUp: T?, regular: T?): T? = wakeUp ?: regular

fun passesGate(lastPassedNanos: Long?, eventNanos: Long, minIntervalNanos: Long): Boolean =
    lastPassedNanos == null || eventNanos - lastPassedNanos >= minIntervalNanos

fun lowPass(previous: Vec3?, sample: Vec3, dtNanos: Long, tauNanos: Long): Vec3 {
    if (previous == null) return sample
    val dt = dtNanos.coerceAtLeast(0L).toFloat()
    val alpha = dt / (tauNanos + dt)
    return Vec3(blend(previous.x, sample.x, alpha), blend(previous.y, sample.y, alpha), blend(previous.z, sample.z, alpha))
}

fun gravitySourceFor(hasGravity: Boolean, hasAccelerometer: Boolean): GravitySource = when {
    hasGravity -> GravitySource.GRAVITY_SENSOR
    hasAccelerometer -> GravitySource.ACCELEROMETER_LOW_PASS
    else -> GravitySource.NONE
}

private fun blend(from: Float, to: Float, alpha: Float): Float = from + alpha * (to - from)
