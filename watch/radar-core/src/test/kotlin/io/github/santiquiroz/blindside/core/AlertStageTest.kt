package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.sim.Scenarios
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
    fun `an esp32 reboot does not make a known contact vibrate again`() {
        val run = runScenario(Scenarios.crossing())
        val restarted = simulate(Scenarios.crossing()).take(10).map { packet ->
            BundleEncoder.encode(rebased(BundleDecoder.decode(packet.bytes)!!, -9_000)) to packet.arrivalNanos + 9_000_000_000L
        }

        val events = restarted.flatMap { (bytes, nanos) -> run.pipeline.onBlePacket(bytes, nanos) }

        assertTrue(events.none { it is ContactAlert })
    }

    @Test
    fun `two people at the same range vibrate one after the other, about 1 s apart`() {
        val run = runScenario(Scenarios.twoPeopleSameRange())

        assertEquals(2, run.alerts.map { it.displayId }.toSet().size)
        val gapMs = scenarioMsOf(run.alerts[1].tNanos) - scenarioMsOf(run.alerts[0].tNanos)
        assertTrue(gapMs in 1_000..1_100, "gap was $gapMs ms")
    }

    private fun rebased(bundle: Bundle, deltaMs: Long): Bundle = bundle.copy(
        tMs = bundle.tMs + deltaMs,
        radarFrames = bundle.radarFrames.map { it.copy(tMs = it.tMs + deltaMs) },
        imuBatches = bundle.imuBatches.map { it.copy(tFirstMs = it.tFirstMs + deltaMs) },
    )
}
