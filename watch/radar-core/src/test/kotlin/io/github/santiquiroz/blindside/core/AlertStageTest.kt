package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.simEspMs
import io.github.santiquiroz.blindside.core.sim.simulate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertStageTest {
    @Test
    fun `a crossing person vibrates once, on the left`() {
        val run = runScenario(Scenarios.crossing())

        assertEquals(listOf(Side.LEFT), run.alerts.map { it.side })
    }

    @Test
    fun `eliminated mode never vibrates for contacts`() {
        val pipeline = RadarPipeline(PipelineConfig())
        pipeline.setEliminated(true)
        val packets = simulate(Scenarios.crossing())
        pipeline.onLinkState(true, packets.first().arrivalNanos - 1)

        val events = packets.flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }

        assertTrue(events.none { it is ContactAlert })
    }

    @Test
    fun `contacts present while eliminated do not vibrate after coming back`() {
        val pipeline = RadarPipeline(PipelineConfig())
        val packets = simulate(Scenarios.crossing())
        pipeline.onLinkState(true, packets.first().arrivalNanos - 1)
        pipeline.setEliminated(true)
        val before = packets.take(40).flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }
        pipeline.setEliminated(false)

        val after = packets.drop(40).flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }

        assertTrue((before + after).none { it is ContactAlert })
    }

    @Test
    fun `an esp32 reboot after a turn does not make a contact still in view vibrate again`() {
        val run = runScenario(Scenarios.turningWithMarcher()) { if (it.tMs >= simEspMs(6_000)) rebased(it, -5_000) else it }

        assertEquals(1, run.pipeline.counters().espResets)
        assertEquals(1, run.alerts.size)
    }

    @Test
    fun `two people at the same range vibrate one after the other, about 1 s apart`() {
        val run = runScenario(Scenarios.twoPeopleSameRange())

        assertEquals(2, run.alerts.map { it.displayId }.toSet().size)
        val gapMs = scenarioMsOf(run.alerts[1].tNanos) - scenarioMsOf(run.alerts[0].tNanos)
        assertTrue(gapMs in 1_000..1_100, "gap was $gapMs ms")
    }

    @Test
    fun `a pending contact missing two windows right before its slot still vibrates`() {
        val scenario = Scenarios.twoPeopleSameRange()
        val firstPacketMs = scenarioMsOf(runScenario(scenario).alerts.first().tNanos) - scenario.bleDelayMs
        val blanked = simEspMs(firstPacketMs + 800) until simEspMs(firstPacketMs + 1_000)

        val run = runScenario(scenario) { bundle ->
            bundle.copy(radarFrames = bundle.radarFrames.map { if (it.tMs in blanked) it.copy(targets = emptyList()) else it })
        }

        assertEquals(2, run.alerts.map { it.displayId }.toSet().size)
    }

    private fun rebased(bundle: Bundle, deltaMs: Long): Bundle = bundle.copy(
        tMs = bundle.tMs + deltaMs,
        radarFrames = bundle.radarFrames.map { it.copy(tMs = it.tMs + deltaMs) },
        imuBatches = bundle.imuBatches.map { it.copy(tFirstMs = it.tFirstMs + deltaMs) },
    )
}
