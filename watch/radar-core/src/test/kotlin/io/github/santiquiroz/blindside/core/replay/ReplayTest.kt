package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.GyroInput
import io.github.santiquiroz.blindside.core.PacketInput
import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.config.toJson
import io.github.santiquiroz.blindside.core.runScenario
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.core.scenarioInputs
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReplayTest {
    @Test
    fun `replaying a recording with watch gyro records gives the same events as the live run`() {
        val scenario = Scenarios.turningWithMarcher()
        val live = runScenario(scenario)

        val (header, records) = readBack(recordingOf(scenario, PipelineConfig()))
        val replayed = replayRecording(records.asSequence(), RadarPipeline(pipelineConfigFromHeader(header)), startOf(scenario))

        assertTrue(live.events.isNotEmpty())
        assertEquals(live.events, replayed)
    }

    @Test
    fun `an info record in the recording reaches the pipeline`() {
        val scenario = Scenarios.crossing()
        val start = startOf(scenario)
        val info = BsrecRecord(RecordType.INFO_REREAD, 0, BsrecPayloads.info("""{"boot_id":"a1"}"""))
        val reboot = BsrecRecord(RecordType.INFO_REREAD, 8_000, BsrecPayloads.info("""{"boot_id":"b2"}"""))
        val pipeline = RadarPipeline(PipelineConfig())

        replayRecording((listOf(info) + recordsOf(scenario, start) + reboot).asSequence(), pipeline, start)

        assertEquals(1, pipeline.counters().espResets)
    }

    @Test
    fun `a recorded link drop replays as a link loss`() {
        val drop = BsrecRecord(RecordType.MODE_CHANGE, 500, BsrecPayloads.linkChange(false))

        val events = replayRecording(sequenceOf(drop), RadarPipeline(PipelineConfig()), startNanos = 0L)

        assertEquals(listOf(SystemAlert(Warning.LINK_LOST, 500_000_000L)), events)
    }

    @Test
    fun `a mode change to eliminated silences the replay`() {
        val scenario = Scenarios.crossing()
        val start = startOf(scenario)
        val mode = BsrecRecord(RecordType.MODE_CHANGE, 0, BsrecPayloads.modeChange(SessionMode.ELIMINATED))

        val events = replayRecording((listOf(mode) + recordsOf(scenario, start)).asSequence(), RadarPipeline(PipelineConfig()), start)

        assertEquals(0, events.filterIsInstance<ContactAlert>().size)
    }

    @Test
    fun `the pipeline config is rebuilt from the recording header`() {
        val config = PipelineConfig(TuningParams(imu = ImuParams(radarImuDelayMs = 40)))

        assertEquals(config, pipelineConfigFromHeader("""{"proto":1,"config":${config.toJson()}}"""))
        assertEquals(PipelineConfig(), pipelineConfigFromHeader("""{"proto":1}"""))
    }

    private fun startOf(scenario: Scenario): Long = scenarioInputs(scenario).first().nanos

    private fun recordsOf(scenario: Scenario, start: Long): List<BsrecRecord> = scenarioInputs(scenario).map { input ->
        val tMs = (input.nanos - start) / 1_000_000L
        when (input) {
            is PacketInput -> BsrecRecord(RecordType.BLE_PACKET, tMs, input.bytes)
            is GyroInput -> BsrecRecord(RecordType.WATCH_GYRO, tMs, BsrecPayloads.watchGyro(input.sample.x, input.sample.y, input.sample.z, input.nanos))
        }
    }

    private fun recordingOf(scenario: Scenario, config: PipelineConfig): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BsrecWriter(out, """{"proto":1,"config":${config.toJson()}}""")
        recordsOf(scenario, startOf(scenario)).forEach { writer.write(it) }
        writer.close()
        return out.toByteArray()
    }

    private fun readBack(bytes: ByteArray): Pair<String, List<BsrecRecord>> {
        val reader = BsrecReader(ByteArrayInputStream(bytes))
        return reader.headerJson to reader.records().toList()
    }
}
