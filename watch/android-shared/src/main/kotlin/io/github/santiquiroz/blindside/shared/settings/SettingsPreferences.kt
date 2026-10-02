package io.github.santiquiroz.blindside.shared.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.santiquiroz.blindside.shared.sensors.GravityTemplate

internal object Keys {
    val HANDEDNESS = stringPreferencesKey("handedness")
    val SCREEN_MODE = stringPreferencesKey("screen_mode")
    val VIBRATION_USAGE = stringPreferencesKey("vibration_usage")
    val ELIMINATED = booleanPreferencesKey("eliminated")
    val BELT_ADDRESS = stringPreferencesKey("belt_address")
    val POSTURE = stringPreferencesKey("watch_posture")
    val AUTO_START_RADAR = booleanPreferencesKey("auto_start_radar")
    val SHARED_UPDATED_MS = longPreferencesKey("shared_updated_ms")
    val CONTACT_COLOR = stringPreferencesKey("contact_color")
    val COMPASS = booleanPreferencesKey("compass")
    val POSTURE_TEMPLATE_SET = booleanPreferencesKey("posture_template_set")
    val POSTURE_TEMPLATE_X = doublePreferencesKey("posture_template_x")
    val POSTURE_TEMPLATE_Y = doublePreferencesKey("posture_template_y")
    val POSTURE_TEMPLATE_Z = doublePreferencesKey("posture_template_z")
    val POSTURE_TEMPLATE_ROT = doublePreferencesKey("posture_template_rot")

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
        postureTemplate = templateFrom(prefs),
        autoStartRadar = prefs[Keys.AUTO_START_RADAR] ?: defaults.autoStartRadar,
        sharedUpdatedMs = prefs[Keys.SHARED_UPDATED_MS] ?: missingStampFor(prefs),
        contactColor = enumOrDefault(prefs[Keys.CONTACT_COLOR], defaults.contactColor),
        compass = prefs[Keys.COMPASS] ?: defaults.compass,
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
    prefs[Keys.CONTACT_COLOR] = settings.contactColor.name
    prefs[Keys.COMPASS] = settings.compass
    writeTemplate(prefs, settings.postureTemplate)
    writeOptional(prefs, Keys.BELT_ADDRESS, settings.beltAddress)
    settings.radars.forEach { writeRadar(prefs, it) }
}

// The grip template is a device-specific calibration, so it stays watch-local and never travels in SharedSettings.
private fun templateFrom(prefs: Preferences): GravityTemplate? {
    if (prefs[Keys.POSTURE_TEMPLATE_SET] != true) return null
    return GravityTemplate(
        x = (prefs[Keys.POSTURE_TEMPLATE_X] ?: 0.0).toFloat(),
        y = (prefs[Keys.POSTURE_TEMPLATE_Y] ?: 0.0).toFloat(),
        z = (prefs[Keys.POSTURE_TEMPLATE_Z] ?: 0.0).toFloat(),
        rotationDeg = (prefs[Keys.POSTURE_TEMPLATE_ROT] ?: 0.0).toFloat(),
    )
}

private fun writeTemplate(prefs: MutablePreferences, template: GravityTemplate?) {
    if (template == null) return removeTemplate(prefs)
    prefs[Keys.POSTURE_TEMPLATE_SET] = true
    prefs[Keys.POSTURE_TEMPLATE_X] = template.x.toDouble()
    prefs[Keys.POSTURE_TEMPLATE_Y] = template.y.toDouble()
    prefs[Keys.POSTURE_TEMPLATE_Z] = template.z.toDouble()
    prefs[Keys.POSTURE_TEMPLATE_ROT] = template.rotationDeg.toDouble()
}

private fun removeTemplate(prefs: MutablePreferences) {
    prefs.remove(Keys.POSTURE_TEMPLATE_SET)
    prefs.remove(Keys.POSTURE_TEMPLATE_X)
    prefs.remove(Keys.POSTURE_TEMPLATE_Y)
    prefs.remove(Keys.POSTURE_TEMPLATE_Z)
    prefs.remove(Keys.POSTURE_TEMPLATE_ROT)
}

// Every write before stamps existed stored the hand, so a hand without a stamp is an older build's calibration.
fun missingStampFor(prefs: Preferences): Long = if (Keys.HANDEDNESS in prefs) MIGRATED_STAMP_MS else AppSettings().sharedUpdatedMs

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
