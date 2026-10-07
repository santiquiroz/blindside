package io.github.santiquiroz.blindside.core.scene

enum class Confidence { BOTH, SINGLE, COASTING }

enum class Side { LEFT, CENTER, RIGHT }

data class Blip(
    val displayId: Int,
    val bearingDeg: Double,
    val rangeM: Double,
    val confidence: Confidence,
    val ageMs: Long,
    val outOfView: Boolean,
)

data class SensorStatus(val id: Int, val alive: Boolean)

enum class MotionState { STILL, TURNING, WALKING, PRONE }

enum class Warning { LINK_LOST, RADAR_DOWN, IMU_DOWN, NO_IMU_COMPENSATION, PRONE, CORRUPT_FRAMES, ALERT_OVERFLOW, YAW_UNCALIBRATED }

data class CoverageSector(val fromDeg: Double, val toDeg: Double)

data class RadarScene(
    val blips: List<Blip>,
    val coverage: List<CoverageSector>,
    val linkUp: Boolean,
    val radars: List<SensorStatus>,
    val imus: List<SensorStatus>,
    val motion: MotionState,
    val warnings: Set<Warning>,
    val eliminated: Boolean,
    val bodyYawDeg: Double = 0.0,
    val yawFromBelt: Boolean = false,
)
