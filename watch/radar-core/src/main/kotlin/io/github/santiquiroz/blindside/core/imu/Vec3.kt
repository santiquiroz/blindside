package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.protocol.ACCEL_LSB_PER_G
import io.github.santiquiroz.blindside.core.protocol.GYRO_LSB_PER_DPS
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import kotlin.math.acos
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val norm: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 = this / norm
    fun maxAbs(): Double = maxOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z))

    fun angleDegTo(o: Vec3): Double = Math.toDegrees(acos(((this dot o) / (norm * o.norm)).coerceIn(-1.0, 1.0)))

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

data class ImuReading(val tMs: Long, val gyroDps: Vec3, val accelG: Vec3)

data class TimedValue(val tMs: Long, val value: Double)

fun ImuBatch.readings(gyroLsbPerDps: Double = GYRO_LSB_PER_DPS, accelLsbPerG: Double = ACCEL_LSB_PER_G): List<ImuReading> =
    samples.mapIndexed { index, s ->
        ImuReading(
            tMs = sampleTimeMs(index),
            gyroDps = Vec3(s.gx.toDouble(), s.gy.toDouble(), s.gz.toDouble()) / gyroLsbPerDps,
            accelG = Vec3(s.ax.toDouble(), s.ay.toDouble(), s.az.toDouble()) / accelLsbPerG,
        )
    }

fun List<Vec3>.mean(): Vec3 = fold(Vec3.ZERO) { acc, v -> acc + v } / size.toDouble()

fun List<Double>.standardDeviation(): Double {
    if (size < 2) return 0.0
    val mean = average()
    return sqrt(sumOf { (it - mean) * (it - mean) } / size)
}
