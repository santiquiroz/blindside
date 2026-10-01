package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B

enum class ScreenMode { SIGILO, VISTA }

enum class VibrationUsage { ALARM, NOTIFICATION }

enum class ContactColor { GREEN, RED }

// Positive turns the drawing clockwise: +90° puts "arriba" at 3 o'clock, the fingers with the watch on the inside of the left wrist.
enum class WatchPosture(val rotationDeg: Float) {
    NORMAL(0f),
    TACTICAL_LEFT(90f),
    TACTICAL_RIGHT(-90f),
}

data class RadarSettings(
    val radarId: Int,
    val yawDegOverride: Double? = null,
    val flipX: Boolean = false,
    val speedSign: Int = 1,
)

data class AppSettings(
    val handedness: Handedness = Handedness.RIGHT,
    val radars: List<RadarSettings> = DEFAULT_RADARS,
    val screenMode: ScreenMode = ScreenMode.SIGILO,
    val vibrationUsage: VibrationUsage = VibrationUsage.ALARM,
    val eliminated: Boolean = false,
    val beltAddress: String? = null,
    val posture: WatchPosture = WatchPosture.NORMAL,
    val autoStartRadar: Boolean = true,
    val sharedUpdatedMs: Long = 0L,
    val contactColor: ContactColor = ContactColor.GREEN,
    val compass: Boolean = true,
)

typealias SettingsTransform = (AppSettings) -> AppSettings

const val YAW_STEP_DEG = 5.0
const val YAW_LIMIT_DEG = 90.0

val DEFAULT_RADARS: List<RadarSettings> = listOf(RadarSettings(RADAR_A), RadarSettings(RADAR_B))

fun AppSettings.radar(radarId: Int): RadarSettings =
    radars.firstOrNull { it.radarId == radarId } ?: RadarSettings(radarId)

fun AppSettings.withRadar(updated: RadarSettings): AppSettings =
    copy(radars = radars.map { if (it.radarId == updated.radarId) updated else it })

fun AppSettings.withHandedness(newHandedness: Handedness): AppSettings =
    copy(handedness = newHandedness, radars = radars.map { it.copy(yawDegOverride = null) })

fun AppSettings.forNewSession(): AppSettings = copy(eliminated = false)

fun AppSettings.withFlipXToggled(radarId: Int): AppSettings =
    withRadar(radar(radarId).let { it.copy(flipX = !it.flipX) })

fun AppSettings.withSpeedSignFlipped(radarId: Int): AppSettings =
    withRadar(radar(radarId).let { it.copy(speedSign = -it.speedSign) })

fun nextHandedness(current: Handedness): Handedness =
    Handedness.entries[(current.ordinal + 1) % Handedness.entries.size]

fun nextPosture(current: WatchPosture): WatchPosture =
    WatchPosture.entries[(current.ordinal + 1) % WatchPosture.entries.size]

fun stepYaw(currentDeg: Double, deltaDeg: Double): Double =
    (currentDeg + deltaDeg).coerceIn(-YAW_LIMIT_DEG, YAW_LIMIT_DEG)

fun toggledScreenMode(mode: ScreenMode): ScreenMode =
    if (mode == ScreenMode.SIGILO) ScreenMode.VISTA else ScreenMode.SIGILO

fun toggledUsage(usage: VibrationUsage): VibrationUsage =
    if (usage == VibrationUsage.ALARM) VibrationUsage.NOTIFICATION else VibrationUsage.ALARM

fun toggledContactColor(color: ContactColor): ContactColor =
    if (color == ContactColor.GREEN) ContactColor.RED else ContactColor.GREEN
