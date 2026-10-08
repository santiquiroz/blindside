package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.defaultMounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineConfigMappingTest {
    private fun mount(config: PipelineConfig, radarId: Int): RadarMount = config.mounts.single { it.radarId == radarId }

    @Test
    fun `default settings use the right-handed nominal yaws`() {
        val config = toPipelineConfig(AppSettings())
        assertEquals(-40.0, mount(config, RADAR_A).yawDeg, 1e-9)
        assertEquals(20.0, mount(config, RADAR_B).yawDeg, 1e-9)
    }

    @Test
    fun `a yaw override replaces only that radar's nominal yaw`() {
        val settings = AppSettings(handedness = Handedness.LEFT)
            .withRadar(RadarSettings(RADAR_B, yawDegOverride = 35.0))
        val config = toPipelineConfig(settings)
        assertEquals(-20.0, mount(config, RADAR_A).yawDeg, 1e-9)
        assertEquals(35.0, mount(config, RADAR_B).yawDeg, 1e-9)
    }

    @Test
    fun `sign settings reach the pipeline mounts`() {
        val settings = AppSettings().withFlipXToggled(RADAR_A).withSpeedSignFlipped(RADAR_B)
        val config = toPipelineConfig(settings)
        assertTrue(mount(config, RADAR_A).flipX)
        assertEquals(1, mount(config, RADAR_A).speedSign)
        assertFalse(mount(config, RADAR_B).flipX)
        assertEquals(-1, mount(config, RADAR_B).speedSign)
    }

    @Test
    fun `mount positions stay at the radar-core defaults`() {
        val expected = defaultMounts(Handedness.RIGHT).sortedBy { it.radarId }.map { it.xM to it.yM }
        val actual = toPipelineConfig(AppSettings()).mounts.sortedBy { it.radarId }.map { it.xM to it.yM }
        assertEquals(expected, actual)
    }

    @Test
    fun `nudging the yaw starts from the effective value`() {
        val nudged = AppSettings().withYawNudged(RADAR_B, YAW_STEP_DEG)
        assertEquals(25.0, effectiveYawDeg(nudged, RADAR_B), 1e-9)
        assertEquals(-40.0, effectiveYawDeg(nudged, RADAR_A), 1e-9)
    }

    @Test
    fun `the doppler filter setting reaches the pipeline tuning`() {
        assertTrue(toPipelineConfig(AppSettings()).tuning.doppler.enabled)
        assertFalse(toPipelineConfig(AppSettings(dopplerFilter = false)).tuning.doppler.enabled)
    }
}
