package io.github.santiquiroz.blindside.shared.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.shared.sensors.GravityTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsPreferencesTest {
    @Test
    fun `empty preferences give the defaults`() {
        assertEquals(AppSettings(), settingsFrom(emptyPreferences()))
    }

    @Test
    fun `every field survives a write and read`() {
        val original = AppSettings(
            handedness = Handedness.SWITCHER,
            radars = listOf(
                RadarSettings(RADAR_A, yawDegOverride = -35.0, flipX = true, speedSign = -1),
                RadarSettings(RADAR_B, yawDegOverride = null, flipX = false, speedSign = 1),
            ),
            screenMode = ScreenMode.VISTA,
            vibrationUsage = VibrationUsage.NOTIFICATION,
            beltAddress = "AA:BB:CC:DD:EE:FF",
            posture = WatchPosture.TACTICAL_RIGHT,
            autoStartRadar = false,
            sharedUpdatedMs = 1_727_790_153_123L,
            contactColor = ContactColor.RED,
            compass = false,
            gameDurationMs = 7_200_000L,
            dopplerFilter = false,
        )
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, original)
        assertEquals(original, settingsFrom(prefs))
    }

    @Test
    fun `unknown enum names fall back to defaults instead of crashing`() {
        val prefs = preferencesOf(
            Keys.HANDEDNESS to "AMBIDEXTROUS",
            Keys.SCREEN_MODE to "NIGHT",
            Keys.VIBRATION_USAGE to "",
            Keys.POSTURE to "UPSIDE_DOWN",
        )
        val settings = settingsFrom(prefs)
        assertEquals(Handedness.RIGHT, settings.handedness)
        assertEquals(ScreenMode.SIGILO, settings.screenMode)
        assertEquals(VibrationUsage.ALARM, settings.vibrationUsage)
        assertEquals(WatchPosture.AUTO, settings.posture)
    }

    @Test
    fun `the posture template survives a write and read, and null clears it`() {
        val withTemplate = AppSettings(postureTemplate = GravityTemplate(1.5f, -2.5f, 9.0f, 90f))
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, withTemplate)
        assertEquals(withTemplate, settingsFrom(prefs))

        writeSettings(prefs, AppSettings(postureTemplate = null))
        assertNull(settingsFrom(prefs).postureTemplate)
    }

    @Test
    fun `the radar starts on open unless it was turned off`() {
        assertTrue(settingsFrom(emptyPreferences()).autoStartRadar)
        assertFalse(settingsFrom(preferencesOf(Keys.AUTO_START_RADAR to false)).autoStartRadar)
    }

    @Test
    fun `a speed sign other than minus one reads as plus one`() {
        val prefs = preferencesOf(Keys.speedSign(RADAR_A) to 7)
        assertEquals(1, settingsFrom(prefs).radar(RADAR_A).speedSign)
    }

    @Test
    fun `settings saved before shared stamps read with the migrated stamp`() {
        assertEquals(MIGRATED_STAMP_MS, settingsFrom(preferencesOf(Keys.HANDEDNESS to "LEFT")).sharedUpdatedMs)
    }

    @Test
    fun `a stored stamp of zero stays zero`() {
        val prefs = preferencesOf(Keys.HANDEDNESS to "LEFT", Keys.SHARED_UPDATED_MS to 0L)
        assertEquals(0L, settingsFrom(prefs).sharedUpdatedMs)
    }

    @Test
    fun `clearing optional values removes their keys`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, AppSettings(beltAddress = "AA:BB:CC:DD:EE:FF").withRadar(RadarSettings(RADAR_B, 30.0)))
        writeSettings(prefs, AppSettings())
        assertNull(prefs[Keys.BELT_ADDRESS])
        assertNull(prefs[Keys.yaw(RADAR_B)])
    }
}
