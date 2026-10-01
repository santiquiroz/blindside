package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.simulateWatchGyro
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplayRecordTest {
    @Test
    fun `feeding records one at a time gives the same events as replaying the whole recording`() {
        val scenario = Scenarios.crossing()
        val packets = simulate(scenario)
        val start = packets.first().arrivalNanos
        val packetRecords = packets.map { BsrecRecord(RecordType.BLE_PACKET, (it.arrivalNanos - start) / 1_000_000L, it.bytes) }
        val gyroRecords = simulateWatchGyro(scenario).filter { it.eventNanos >= start }.map {
            BsrecRecord(RecordType.WATCH_GYRO, (it.eventNanos - start) / 1_000_000L, BsrecPayloads.watchGyro(it.x, it.y, it.z, it.eventNanos))
        }
        val records = (packetRecords + gyroRecords).sortedBy { it.tMsSinceStart }
        val config = PipelineConfig(mounts = scenario.mounts)

        val whole = replayRecording(records.asSequence(), RadarPipeline(config))
        val pipeline = RadarPipeline(config).also { it.onLinkState(true, 0L) }
        val stepwise = records.flatMap { replayRecord(it, pipeline, it.tMsSinceStart * 1_000_000L) }

        assertTrue(whole.isNotEmpty())
        assertEquals(whole, stepwise)
    }
}
