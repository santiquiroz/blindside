package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.GyroInput
import io.github.santiquiroz.blindside.core.PacketInput
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.scenarioInputs
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.Turn
import io.github.santiquiroz.blindside.core.sim.walker
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TauSweepTest {
    // Spec §12, 4-oct recording: a friend paces slowly in front while the player turns one way and the other.
    private val pacing = Scenario(
        name = "tau-sweep",
        player = listOf(Stand(Scenarios.WARMUP_MS + 1_500)) + List(4) { i -> Turn(1_000, rateDps = if (i % 2 == 0) 90.0 else -90.0) } + Stand(1_000),
        targets = listOf(walker(Scenarios.WARMUP_MS, Scenarios.WARMUP_MS + 7_500, start = Point2(1.0, 2.8), velocityMps = Point2(0.3, 0.0))),
        radarLatencyMs = 100,
    )

    @Test
    fun `the sweep over a recording with 100 ms of radar latency bottoms out at 100 ms`() {
        val inputs = scenarioInputs(pacing)
        val start = inputs.first().nanos
        val records = inputs.map { input ->
            val tMs = (input.nanos - start) / 1_000_000L
            when (input) {
                is PacketInput -> BsrecRecord(RecordType.BLE_PACKET, tMs, input.bytes)
                is GyroInput -> BsrecRecord(RecordType.WATCH_GYRO, tMs, BsrecPayloads.watchGyro(input.sample.x, input.sample.y, input.sample.z, input.nanos))
            }
        }

        val sweep = sweepTau(records, PipelineConfig(), start)

        val best = bestTau(sweep)!!
        assertTrue(best in 80L..120L, "best tau was $best ms, sweep $sweep")
    }
}
