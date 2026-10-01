package io.github.santiquiroz.blindside.shared.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object Keys {
    val HANDEDNESS = stringPreferencesKey("handedness")
    val SCREEN_MODE = stringPreferencesKey("screen_mode")
    val VIBRATION_USAGE = stringPreferencesKey("vibration_usage")
    val ELIMINATED = booleanPreferencesKey("eliminated")
    val BELT_ADDRESS = stringPreferencesKey("belt_address")
    val POSTURE = stringPreferencesKey("watch_posture")
    val AUTO_START_RADAR = booleanPreferencesKey("auto_start_radar")
    val SHARED_UPDATED_MS = longPreferencesKey("shared_updated_ms")

    fun yaw(radarId: Int) = doublePreferencesKey("radar${radarId}_yaw_deg")
    fun flipX(radarId: Int) = booleanPreferencesKey("radar${radarId}_flip_x")
    fun speedSign(radarId: Int) = intPreferencesKey("radar${radarId}_speed_sign")
}

fun settingsFrom(prefs: Preferences): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        handedness = enumOrDefault(prefs[Keys.HANDEDNESS], defaults.handedness),
        radars = DEFAULT_RADARS.map { radarFrom(prefs, it.radarId) },
        screenMode = enumOrDefault(prefs[Keys.SCREEN_MODE], defaults.screenMode),
        vibrationUsage = enumOrDefault(prefs[Keys.VIBRATION_USAGE], defaults.vibrationUsage),
        eliminated = prefs[Keys.ELIMINATED] ?: defaults.eliminated,
        beltAddress = prefs[Keys.BELT_ADDRESS],
        posture = enumOrDefault(prefs[Keys.POSTURE], defaults.posture),
        autoStartRadar = prefs[Keys.AUTO_START_RADAR] ?: defaults.autoStartRadar,
        sharedUpdatedMs = prefs[Keys.SHARED_UPDATED_MS] ?: defaults.sharedUpdatedMs,
    )
}

fun writeSettings(prefs: MutablePreferences, settings: AppSettings) {
    prefs[Keys.HANDEDNESS] = settings.handedness.name
    prefs[Keys.SCREEN_MODE] = settings.screenMode.name
    prefs[Keys.VIBRATION_USAGE] = settings.vibrationUsage.name
    prefs[Keys.ELIMINATED] = settings.eliminated
    prefs[Keys.POSTURE] = settings.posture.name
    prefs[Keys.AUTO_START_RADAR] = settings.autoStartRadar
    prefs[Keys.SHARED_UPDATED_MS] = settings.sharedUpdatedMs
    writeOptional(prefs, Keys.BELT_ADDRESS, settings.beltAddress)
    settings.radars.forEach { writeRadar(prefs, it) }
}

fun parseSpeedSign(stored: Int?): Int = if (stored == -1) -1 else 1

inline fun <reified E : Enum<E>> enumOrDefault(stored: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == stored } ?: default

private fun radarFrom(prefs: Preferences, radarId: Int) = RadarSettings(
    radarId = radarId,
    yawDegOverride = prefs[Keys.yaw(radarId)],
    flipX = prefs[Keys.flipX(radarId)] ?: false,
    speedSign = parseSpeedSign(prefs[Keys.speedSign(radarId)]),
)

private fun writeRadar(prefs: MutablePreferences, radar: RadarSettings) {
    writeOptional(prefs, Keys.yaw(radar.radarId), radar.yawDegOverride)
    prefs[Keys.flipX(radar.radarId)] = radar.flipX
    prefs[Keys.speedSign(radar.radarId)] = radar.speedSign
}

private fun <T> writeOptional(prefs: MutablePreferences, key: Preferences.Key<T>, value: T?) {
    if (value == null) prefs.remove(key) else prefs[key] = value
}
