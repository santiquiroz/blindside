package io.github.santiquiroz.blindside.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigTest {
    @Test
    fun `tuning defaults carry the spec values`() {
        val tuning = TuningParams()

        assertEquals(0.8, tuning.decode.nearFieldM)
        assertEquals(0.4, tuning.tracking.processNoise)
        assertEquals(9.21, tuning.tracking.gateChi2)
        assertEquals(3, tuning.tracking.confirmHits)
        assertEquals(5, tuning.tracking.confirmWindows)
        assertEquals(500L, tuning.tracking.stopScanTailMs)
        assertEquals(6000L, tuning.tracking.coastStillMs)
        assertEquals(1500L, tuning.tracking.coastMovingMs)
        assertEquals(1000L, tuning.alerts.minGapMs)
        assertEquals(10, tuning.alerts.maxPerMinute)
        assertEquals(20.0, tuning.motion.turningRateDps)
    }

    @Test
    fun `contacts are accepted up to 10 m and alert as near up to 6 m`() {
        val tuning = TuningParams()

        assertEquals(10.0, tuning.decode.maxRangeM)
        assertEquals(6.0, tuning.alerts.nearRangeM)
        assertEquals(1000L, tuning.alerts.farMinGapMs)
        assertEquals(6, tuning.alerts.maxFarPerMinute)
    }

    @Test
    fun `the radar to imu delay defaults to 100 ms, not the 0 of the notes`() {
        assertEquals(100L, TuningParams().imu.radarImuDelayMs)
    }

    @Test
    fun `right handed mounts open minus 40 and plus 20`() {
        val mounts = defaultMounts(Handedness.RIGHT)

        assertEquals(listOf(-40.0, 20.0), mounts.map { it.yawDeg })
        assertEquals(listOf(-0.15, 0.15), mounts.map { it.xM })
        assertEquals(listOf(RADAR_A, RADAR_B), mounts.map { it.radarId })
    }

    @Test
    fun `left handed and switcher mounts follow the profile table`() {
        assertEquals(listOf(-20.0, 40.0), defaultMounts(Handedness.LEFT).map { it.yawDeg })
        assertEquals(listOf(-30.0, 30.0), defaultMounts(Handedness.SWITCHER).map { it.yawDeg })
    }

    @Test
    fun `measured yaws keep the nominal mean heading and the measured spread`() {
        val mounts = mountsFromMeasuredYaws(Handedness.RIGHT, measuredYawADeg = -50.0, measuredYawBDeg = 20.0)

        assertEquals(listOf(-45.0, 25.0), mounts.map { it.yawDeg })
    }

    @Test
    fun `pipeline config defaults to the right handed profile`() {
        assertEquals(defaultMounts(Handedness.RIGHT), PipelineConfig().mounts)
    }
}
