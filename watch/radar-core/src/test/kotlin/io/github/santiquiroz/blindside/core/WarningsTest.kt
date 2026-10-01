package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.simArrivalNanos
import io.github.santiquiroz.blindside.core.sim.simulate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarningsTest {
    @Test
    fun `a dropped link raises one LINK_LOST system alert and greys the scene`() {
        val run = runScenario(Scenarios.crossing())

        val events = run.pipeline.onLinkState(false, simArrivalNanos(8_600))
        val again = run.pipeline.onLinkState(false, simArrivalNanos(8_700))

        assertEquals(listOf(Warning.LINK_LOST), events.filterIsInstance<SystemAlert>().map { it.kind })
        assertTrue(again.isEmpty())
        val scene = run.pipeline.scene(simArrivalNanos(8_700))
        assertFalse(scene.linkUp)
        assertTrue(Warning.LINK_LOST in scene.warnings)
    }

    @Test
    fun `a radar going down raises RADAR_DOWN once and shrinks the coverage`() {
        val run = runScenario(Scenarios.crossing().copy(radarDownFromMs = mapOf(1 to 4_000L)))

        assertEquals(1, run.events.filterIsInstance<SystemAlert>().count { it.kind == Warning.RADAR_DOWN })
        val scene = run.sceneAt(5_000)
        assertTrue(Warning.RADAR_DOWN in scene.warnings)
        assertEquals(1, scene.coverage.size)
    }

    @Test
    fun `the gyro is uncalibrated until the first still 2 s window`() {
        val run = runScenario(Scenarios.crossing())

        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(1_500).warnings)
        assertFalse(Warning.YAW_UNCALIBRATED in run.sceneAt(2_500).warnings)
    }

    @Test
    fun `without a watch gyroscope the bias stays unverified and the prompt stays up`() {
        val run = runScenario(Scenarios.crossing().copy(watchGyroPeriodMs = null))

        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(8_000).warnings)
    }

    @Test
    fun `one imu flag down warns IMU_DOWN, both down warns NO_IMU_COMPENSATION`() {
        val oneDown = runScenario(Scenarios.crossing()) { it.copy(flags = 0x0B) }
        val bothDown = runScenario(Scenarios.crossing()) { it.copy(flags = 0x03) }

        assertTrue(Warning.IMU_DOWN in oneDown.sceneAt(3_000).warnings)
        assertTrue(Warning.NO_IMU_COMPENSATION in bothDown.sceneAt(3_000).warnings)
    }

    @Test
    fun `malformed and truncated packets raise CORRUPT_FRAMES`() {
        val pipeline = RadarPipeline(PipelineConfig())
        pipeline.onLinkState(true, simArrivalNanos(0))
        val good = simulate(Scenarios.crossing())[0]
        pipeline.onBlePacket(good.bytes, good.arrivalNanos)

        repeat(10) { pipeline.onBlePacket(byteArrayOf(9, 9, 9), good.arrivalNanos + it) }
        repeat(10) { pipeline.onBlePacket(good.bytes.copyOfRange(0, good.bytes.size - 3), good.arrivalNanos + 100 + it) }

        assertTrue(Warning.CORRUPT_FRAMES in pipeline.scene(good.arrivalNanos + 200).warnings)
    }

    @Test
    fun `a system alert keeps contact alerts waiting for the length of its pattern`() {
        val config = PipelineConfig()
        val alert = SystemAlert(Warning.LINK_LOST, 5_000_000_000L)

        val stage = withSystemAlerts(PipelineState.initial(config), listOf(alert), config)

        assertEquals(listOf(alert), stage.events)
        assertEquals(5_000_000_000L + config.tuning.alerts.systemPatternMs * 1_000_000L, stage.state.limiter.systemBusyUntilNanos)
    }

    @Test
    fun `radar down is only reported on an alive to down edge`() {
        val config = PipelineConfig()
        val bundle = BundleDecoder.decode(simulate(Scenarios.crossing())[0].bytes)!!
        val down = BundleDecoder.decode(BundleEncoder.encode(bundle.copy(flags = 0x0D)))!!

        assertEquals(1, radarDownAlerts(0x0F, down.flags, config, 1L).size)
        assertTrue(radarDownAlerts(0x0D, down.flags, config, 1L).isEmpty())
        assertTrue(radarDownAlerts(null, down.flags, config, 1L).isEmpty())
    }
}
