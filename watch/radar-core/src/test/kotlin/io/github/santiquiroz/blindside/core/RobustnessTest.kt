package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.Walk
import io.github.santiquiroz.blindside.core.sim.walker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RobustnessTest {
    @Test
    fun `a session started while walking alerts after the stop, before the gyro is calibrated`() {
        val scenario = Scenario(
            name = "started-walking",
            player = listOf(Walk(3_000, speedMps = 1.2), Stand(4_000)),
            targets = listOf(walker(3_200, 6_000, start = Point2(-3.0, 5.0), velocityMps = Point2(1.2, 0.0))),
        )

        val run = runScenario(scenario)

        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(2_500).warnings)
        assertEquals(1, run.alerts.size)
        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(scenarioMsOf(run.alerts.single().tNanos)).warnings)
        assertFalse(Warning.YAW_UNCALIBRATED in run.sceneAt(6_900).warnings)
    }

    @Test
    fun `with both imu cables down contacts still alert and the loss is shown`() {
        val run = runScenario(Scenarios.crossing()) { it.copy(flags = it.flags and 0x03) }

        assertEquals(1, run.alerts.size)
        assertTrue(Warning.NO_IMU_COMPENSATION in run.sceneAt(5_000).warnings)
    }

    @Test
    fun `a 2 s ble dropout keeps one id for a rival walking straight through it`() {
        val run = runScenario(Scenarios.crossing().copy(droppedSeqs = (40..59).toSet()))

        assertEquals(20L, run.pipeline.counters().lostPackets)
        assertEquals(1, run.confirmations.size)
        assertEquals(1, run.alerts.size)
    }

    @Test
    fun `sequence numbers wrapping past 65535 are not losses or resets`() {
        val run = runScenario(Scenarios.crossing()) { it.copy(seq = (it.seq + 65_500) and 0xFFFF) }

        assertEquals(0L, run.pipeline.counters().lostPackets)
        assertEquals(0, run.pipeline.counters().espResets)
        assertEquals(1, run.alerts.size)
    }

    @Test
    fun `two box imus sampling 7 ms apart do not turn a still object into a contact while turning`() {
        val run = runScenario(Scenarios.turningWithStillTarget().copy(imuPhaseOffsetMs = listOf(0, 7)))

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.alerts.isEmpty())
    }
}
