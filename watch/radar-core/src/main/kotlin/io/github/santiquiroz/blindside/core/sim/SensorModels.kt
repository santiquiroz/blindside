package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.protocol.ACCEL_LSB_PER_G
import io.github.santiquiroz.blindside.core.protocol.GYRO_LSB_PER_DPS
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import io.github.santiquiroz.blindside.core.protocol.Ld2450Codec
import io.github.santiquiroz.blindside.core.protocol.RawTarget
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

const val SIM_CONE_HALF_ANGLE_DEG = 60.0
const val SIM_MAX_RANGE_M = 6.0
const val SIM_MIN_APPARENT_SPEED_MPS = 0.1
const val SIM_RESOLUTION_MM = 360
private const val SPEED_PROBE_MS = 50L
private const val WALK_BOB_G = 0.25
private const val STEP_HZ = 2.0

// The LD2450 only reports what moves relative to it, so a still person vanishes and a wall "moves" while you turn or walk.
// A frame stamped tMs describes the world radarLatencyMs earlier (the module's internal latency, spec §6.3).
fun radarTargets(scenario: Scenario, mount: RadarMount, tMs: Long): List<RawTarget> {
    val seenMs = tMs - scenario.radarLatencyMs
    val pose = poseAt(scenario.player, seenMs)
    val earlier = poseAt(scenario.player, seenMs - SPEED_PROBE_MS)
    return scenario.targets.mapNotNull { target ->
        val now = target.positionAt(seenMs) ?: return@mapNotNull null
        val before = target.positionAt(seenMs - SPEED_PROBE_MS) ?: now
        val local = worldToRadar(now, pose, mount)
        val localBefore = worldToRadar(before, earlier, mount)
        val apparentSpeed = (local - localBefore).norm * 1000.0 / SPEED_PROBE_MS
        if (!isVisible(local.x, local.y) || apparentSpeed <= SIM_MIN_APPARENT_SPEED_MPS) return@mapNotNull null
        val radialSpeed = (local.norm - localBefore.norm) * 1000.0 / SPEED_PROBE_MS
        local.norm to rawTarget(local.x, local.y, radialSpeed, mount)
    }.sortedBy { it.first }.take(Ld2450Codec.TARGET_COUNT).map { it.second }
}

// One 50 Hz sample is the average over its 20 ms block, centred on centreMs (contracts, "IMU timing").
fun imuSample(scenario: Scenario, imuId: Int, centreMs: Long): ImuSample {
    val pose = poseAt(scenario.player, centreMs)
    val bias = scenario.gyroBiasDps[imuId]
    val bob = if (pose.walking) WALK_BOB_G * sin(2 * PI * STEP_HZ * centreMs / 1000.0) else 0.0
    // Chip z axis up: a clockwise (rightward) turn is a negative rate about z.
    val gyro = Vec3(bias.x, bias.y, bias.z - blockYawRateDps(scenario, centreMs))
    return ImuSample(
        ax = 0,
        ay = 0,
        az = ((1.0 + bob) * ACCEL_LSB_PER_G).roundToInt(),
        gx = (gyro.x * GYRO_LSB_PER_DPS).roundToInt(),
        gy = (gyro.y * GYRO_LSB_PER_DPS).roundToInt(),
        gz = (gyro.z * GYRO_LSB_PER_DPS).roundToInt(),
    )
}

fun blockYawRateDps(scenario: Scenario, centreMs: Long): Double {
    val half = IMU_SAMPLE_PERIOD_MS / 2
    val turned = poseAt(scenario.player, centreMs + half).headingDeg - poseAt(scenario.player, centreMs - half).headingDeg
    return turned * 1000.0 / IMU_SAMPLE_PERIOD_MS
}

private fun isVisible(x: Double, y: Double): Boolean {
    val range = kotlin.math.hypot(x, y)
    val bearing = Math.toDegrees(kotlin.math.atan2(x, y))
    return y > 0 && range <= SIM_MAX_RANGE_M && abs(bearing) <= SIM_CONE_HALF_ANGLE_DEG
}

private fun rawTarget(xM: Double, yM: Double, radialSpeedMps: Double, mount: RadarMount): RawTarget {
    val xSign = if (mount.flipX) -1 else 1
    return RawTarget(
        xMm = (xSign * xM * 1000).roundToInt(),
        yMm = (yM * 1000).roundToInt(),
        speedCms = (mount.speedSign * radialSpeedMps * 100).roundToInt(),
        resolutionMm = SIM_RESOLUTION_MM,
    )
}
