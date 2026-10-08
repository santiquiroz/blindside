package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSettingsTest {
    @Test
    fun `changing handedness clears yaw overrides`() {
        val tuned = AppSettings().withRadar(RadarSettings(RADAR_B, yawDegOverride = 33.0, flipX = true))
        val changed = tuned.withHandedness(Handedness.LEFT)
        assertEquals(Handedness.LEFT, changed.handedness)
        assertNull(changed.radar(RADAR_B).yawDegOverride)
        assertTrue(changed.radar(RADAR_B).flipX)
    }

    @Test
    fun `yaw steps are clamped to plus minus ninety degrees`() {
        assertEquals(90.0, stepYaw(88.0, 5.0), 1e-9)
        assertEquals(-90.0, stepYaw(-88.0, -5.0), 1e-9)
        assertEquals(-35.0, stepYaw(-40.0, 5.0), 1e-9)
    }

    @Test
    fun `handedness cycles through every profile`() {
        assertEquals(Handedness.LEFT, nextHandedness(Handedness.RIGHT))
        assertEquals(Handedness.SWITCHER, nextHandedness(Handedness.LEFT))
        assertEquals(Handedness.RIGHT, nextHandedness(Handedness.SWITCHER))
    }

    @Test
    fun `sign toggles touch only the requested radar`() {
        val toggled = AppSettings().withFlipXToggled(RADAR_A).withSpeedSignFlipped(RADAR_A)
        assertTrue(toggled.radar(RADAR_A).flipX)
        assertEquals(-1, toggled.radar(RADAR_A).speedSign)
        assertEquals(RadarSettings(RADAR_B), toggled.radar(RADAR_B))
    }

    @Test
    fun `screen mode and vibration usage toggle between their two values`() {
        assertEquals(ScreenMode.VISTA, toggledScreenMode(ScreenMode.SIGILO))
        assertEquals(ScreenMode.SIGILO, toggledScreenMode(ScreenMode.VISTA))
        assertEquals(VibrationUsage.NOTIFICATION, toggledUsage(VibrationUsage.ALARM))
        assertEquals(VibrationUsage.ALARM, toggledUsage(VibrationUsage.NOTIFICATION))
    }

    @Test
    fun `contacts are green by default and the colour toggles with red`() {
        assertEquals(ContactColor.GREEN, AppSettings().contactColor)
        assertEquals(ContactColor.RED, toggledContactColor(ContactColor.GREEN))
        assertEquals(ContactColor.GREEN, toggledContactColor(ContactColor.RED))
    }

    @Test
    fun `the compass ring is on by default`() {
        assertEquals(true, AppSettings().compass)
    }

    @Test
    fun `the doppler filter is on by default`() {
        assertEquals(true, AppSettings().dopplerFilter)
    }
}
