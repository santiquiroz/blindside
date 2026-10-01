package io.github.santiquiroz.blindside.core.config

enum class Handedness { RIGHT, LEFT, SWITCHER }

data class RadarMount(
    val radarId: Int,
    val xM: Double,
    val yM: Double,
    val yawDeg: Double,
    val flipX: Boolean = false,
    val speedSign: Int = 1,
)

data class PipelineConfig(
    val tuning: TuningParams = TuningParams(),
    val mounts: List<RadarMount> = defaultMounts(Handedness.RIGHT),
)

const val RADAR_A = 0
const val RADAR_B = 1
private const val MOUNT_OFFSET_M = 0.15

fun nominalYaws(handedness: Handedness): Pair<Double, Double> = when (handedness) {
    Handedness.RIGHT -> -40.0 to 20.0
    Handedness.LEFT -> -20.0 to 40.0
    Handedness.SWITCHER -> -30.0 to 30.0
}

fun defaultMounts(handedness: Handedness): List<RadarMount> {
    val (yawA, yawB) = nominalYaws(handedness)
    return mountsWithYaws(yawA, yawB)
}

// Spec §6.8: without an absolute heading calibration keep the profile's nominal mean and apply only the measured spread.
fun mountsFromMeasuredYaws(handedness: Handedness, measuredYawADeg: Double, measuredYawBDeg: Double): List<RadarMount> {
    val (nominalA, nominalB) = nominalYaws(handedness)
    val nominalMean = (nominalA + nominalB) / 2.0
    val halfSpread = (measuredYawBDeg - measuredYawADeg) / 2.0
    return mountsWithYaws(nominalMean - halfSpread, nominalMean + halfSpread)
}

private fun mountsWithYaws(yawADeg: Double, yawBDeg: Double): List<RadarMount> = listOf(
    RadarMount(radarId = RADAR_A, xM = -MOUNT_OFFSET_M, yM = 0.0, yawDeg = yawADeg),
    RadarMount(radarId = RADAR_B, xM = MOUNT_OFFSET_M, yM = 0.0, yawDeg = yawBDeg),
)
