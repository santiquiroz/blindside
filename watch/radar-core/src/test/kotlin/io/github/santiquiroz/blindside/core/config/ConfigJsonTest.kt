package io.github.santiquiroz.blindside.core.config

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigJsonTest {
    @Test
    fun `the default config round trips`() {
        val config = PipelineConfig()

        assertEquals(config, pipelineConfigFromJson(config.toJson()))
    }

    @Test
    fun `a tuned config with flipped mounts round trips`() {
        val tuning = TuningParams(imu = ImuParams(radarImuDelayMs = 40), alerts = AlertParams(minGapMs = 1_200))
        val mounts = mountsFromMeasuredYaws(Handedness.LEFT, -25.0, 37.5).map { it.copy(flipX = true, speedSign = -1) }
        val config = PipelineConfig(tuning, mounts)

        assertEquals(config, pipelineConfigFromJson(config.toJson()))
    }

    @Test
    fun `the json is a plain object with one key per parameter group`() {
        val root = MiniJson.parse(PipelineConfig().toJson()) as Map<*, *>
        val tuning = root["tuning"] as Map<*, *>

        assertEquals(setOf("decode", "imu", "motion", "tracking", "alerts", "clock", "status"), tuning.keys)
        assertEquals(100.0, (tuning["imu"] as Map<*, *>)["radarImuDelayMs"])
        assertEquals(listOf(320.0, 360.0), (tuning["decode"] as Map<*, *>)["validResolutionsMm"])
    }

    @Test
    fun `every property of every parameter group is written`() {
        val tuning = (MiniJson.parse(PipelineConfig().toJson()) as Map<*, *>)["tuning"] as Map<*, *>
        val groups = mapOf(
            "decode" to DecodeParams::class.java, "imu" to ImuParams::class.java, "motion" to MotionParams::class.java,
            "tracking" to TrackingParams::class.java, "alerts" to AlertParams::class.java, "clock" to ClockParams::class.java,
            "status" to StatusParams::class.java,
        )

        groups.forEach { (name, type) -> assertEquals(type.declaredFields.size, (tuning[name] as Map<*, *>).size, name) }
    }

    @Test
    fun `missing keys keep their defaults`() {
        val config = pipelineConfigFromJson("""{"tuning":{"imu":{"radarImuDelayMs":70}}}""")

        assertEquals(PipelineConfig(TuningParams(imu = ImuParams(radarImuDelayMs = 70))), config)
    }

    @Test
    fun `unreadable json gives the default config`() {
        assertEquals(PipelineConfig(), pipelineConfigFromJson("{broken"))
        assertTrue(pipelineConfigFromValue(null).mounts.isNotEmpty())
    }
}
