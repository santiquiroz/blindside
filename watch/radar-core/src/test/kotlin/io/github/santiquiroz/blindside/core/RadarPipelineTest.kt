package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.protocol.LinkParams
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.Turn
import io.github.santiquiroz.blindside.core.sim.simArrivalNanos
import io.github.santiquiroz.blindside.core.sim.simEspMs
import io.github.santiquiroz.blindside.core.sim.simulate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarPipelineTest {
    private val config = PipelineConfig()

    @Test
    fun `a crossing person is confirmed once`() {
        val run = runScenario(Scenarios.crossing())

        assertEquals(1, run.confirmations.size)
    }

    @Test
    fun `a 45 degree turn with the two imus 7 ms apart reads 45 degrees`() {
        val state = ingestAll(Scenarios.turningWithMarcher())

        assertEquals(45.0, state.yaw.yawAt(simEspMs(7_000), config.tuning.imu), 1.0)
    }

    @Test
    fun `lost packets are counted and the turn is rebuilt across the gap`() {
        val run = runScenario(Scenarios.turningWithMarcher().copy(droppedSeqs = (51..58).toSet()))

        assertEquals(8L, run.pipeline.counters().lostPackets)
        assertEquals(-45.0, run.sceneAt(7_600).blips.single().bearingDeg, 8.0)
    }

    @Test
    fun `an info scale of 32_8 LSB per deg per s doubles the turn read from the same counts`() {
        val info = """{"boot_id":"a1","imus":[{"id":0,"gyro_lsb_dps":32.8},{"id":1,"gyro_lsb_dps":32.8}]}"""

        val state = ingestAll(Scenarios.turningWithMarcher(), info)

        assertEquals(90.0, state.yaw.yawAt(simEspMs(7_000), config.tuning.imu), 2.0)
    }

    @Test
    fun `a new boot id restarts the time references, the same one does not`() {
        val running = ingestAll(Scenarios.crossing(), """{"boot_id":"a1"}""")

        val reread = withBeltInfo(running, """{"boot_id":"a1"}""")
        val rebooted = withBeltInfo(running, """{"boot_id":"b2"}""")

        assertEquals(0, reread.counters.espResets)
        assertTrue(reread.clock.isReady)
        assertEquals(1, rebooted.counters.espResets)
        assertFalse(rebooted.clock.isReady)
        assertEquals("b2", rebooted.bootId)
    }

    @Test
    fun `t_ms going backwards restarts the time references`() {
        val run = runScenario(Scenarios.crossing())
        val restarted = simulate(Scenarios.crossing()).take(10).map { packet ->
            BundleEncoder.encode(rebased(BundleDecoder.decode(packet.bytes)!!, -9_000)) to packet.arrivalNanos + 9_000_000_000L
        }

        restarted.forEach { (bytes, nanos) -> run.pipeline.onBlePacket(bytes, nanos) }

        assertEquals(1, run.pipeline.counters().espResets)
    }

    @Test
    fun `with both box imus down the watch gyroscope says the player is turning`() {
        val turn = Scenario("watch-turn", player = listOf(Stand(Scenarios.WARMUP_MS), Turn(1_500, rateDps = 30.0), Stand(1_000)))

        val run = runScenario(turn) { it.copy(flags = it.flags and 0x03) }

        assertEquals(MotionState.TURNING, run.sceneAt(Scenarios.WARMUP_MS + 1_000).motion)
        assertEquals(MotionState.STILL, run.sceneAt(Scenarios.WARMUP_MS - 500).motion)
    }

    @Test
    fun `a watch step marks the player as walking for 1_2 s`() {
        val run = runScenario(Scenarios.crossing())

        run.pipeline.onWatchStep(simArrivalNanos(8_480, 0))

        assertEquals(MotionState.WALKING, run.pipeline.scene(simArrivalNanos(8_500)).motion)
    }

    @Test
    fun `with both box imus down a watch step heard 1_5 s late still marks the player as walking`() {
        val run = runScenario(Scenarios.crossing()) { it.copy(flags = it.flags and 0x03) }

        run.pipeline.onWatchStep(simArrivalNanos(7_000, 0))

        assertEquals(MotionState.WALKING, run.pipeline.scene(simArrivalNanos(8_500)).motion)
        assertEquals(MotionState.STILL, run.pipeline.scene(simArrivalNanos(9_800)).motion)
    }

    @Test
    fun `no packets for more than 1 s means the link is not up`() {
        val run = runScenario(Scenarios.crossing())

        assertTrue(run.pipeline.scene(simArrivalNanos(8_500)).linkUp)
        assertFalse(run.pipeline.scene(simArrivalNanos(9_700)).linkUp)
    }

    @Test
    fun `eliminated mode hides every contact`() {
        val run = runScenario(Scenarios.crossing())

        run.pipeline.setEliminated(true)

        assertTrue(run.pipeline.scene(simArrivalNanos(5_000)).eliminated)
        assertTrue(run.pipeline.scene(simArrivalNanos(5_000)).blips.isEmpty())
    }

    @Test
    fun `a LINK section updates the link counters`() {
        val run = runScenario(Scenarios.crossing()) { if (it.seq == 5) it.copy(links = listOf(LinkParams(36, 0, 500))) else it }

        assertEquals(LinkParams(36, 0, 500), run.pipeline.counters().lastLink)
    }

    @Test
    fun `malformed and truncated packets are counted`() {
        val pipeline = RadarPipeline(config)
        pipeline.onLinkState(true, simArrivalNanos(0))
        val good = simulate(Scenarios.crossing())[0]
        pipeline.onBlePacket(good.bytes, good.arrivalNanos)

        repeat(10) { pipeline.onBlePacket(byteArrayOf(9, 9, 9), good.arrivalNanos + it) }
        repeat(10) { pipeline.onBlePacket(good.bytes.copyOfRange(0, good.bytes.size - 3), good.arrivalNanos + 100 + it) }

        assertEquals(10, pipeline.counters().malformedPackets)
        assertEquals(10, pipeline.counters().truncatedPackets)
    }

    @Test
    fun `updates made while turning feed the NIS used to tune tau`() {
        val counters = runScenario(Scenarios.turningWithMarcher()).pipeline.counters()

        assertTrue(counters.turningNisCount > 0)
        assertTrue(counters.meanTurningNis!! > 0.0)
    }

    private fun ingestAll(scenario: Scenario, infoJson: String? = null): PipelineState {
        val start = withLinkState(PipelineState.initial(config), connected = true)
        val withInfo = infoJson?.let { withBeltInfo(start, it) } ?: start
        return scenarioInputs(scenario).fold(withInfo) { state, input ->
            when (input) {
                is PacketInput -> ingestPacket(state, input.bytes, input.nanos, config).state
                is GyroInput -> withWatchGyro(state, input.sample.x, input.sample.y, input.sample.z, input.nanos, config)
            }
        }
    }

    private fun rebased(bundle: Bundle, deltaMs: Long): Bundle = bundle.copy(
        tMs = bundle.tMs + deltaMs,
        radarFrames = bundle.radarFrames.map { it.copy(tMs = it.tMs + deltaMs) },
        imuBatches = bundle.imuBatches.map { it.copy(tFirstMs = it.tFirstMs + deltaMs) },
    )
}
