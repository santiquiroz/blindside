package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.pipelineConfigFromValue
import io.github.santiquiroz.blindside.core.protocol.MiniJson

// The app writes the session's PipelineConfig.toJson() under "config" in the .bsrec header (spec §6.10).
fun pipelineConfigFromHeader(headerJson: String): PipelineConfig =
    pipelineConfigFromValue((MiniJson.parseOrNull(headerJson) as? Map<*, *>)?.get("config"))

// Deterministic replay: the recording's own clock drives the pipeline; the initial link-up only matters for recordings without link records.
fun replayRecording(records: Sequence<BsrecRecord>, pipeline: RadarPipeline, startNanos: Long = 0): List<PipelineEvent> {
    pipeline.onLinkState(true, startNanos)
    return records.flatMap { record -> replayRecord(record, pipeline, startNanos + record.tMsSinceStart * NANOS_PER_MS) }.toList()
}

private fun replayRecord(record: BsrecRecord, pipeline: RadarPipeline, nanos: Long): List<PipelineEvent> = when (record.type) {
    RecordType.BLE_PACKET -> pipeline.onBlePacket(record.payload, nanos)
    RecordType.MODE_CHANGE -> replayModeChange(record.payload, pipeline, nanos)
    else -> emptyList<PipelineEvent>().also { replayWatchInput(record, pipeline, nanos) }
}

// The app records link transitions as LINK_UP/LINK_DOWN mode changes; they go to onLinkState exactly as they did live.
private fun replayModeChange(payload: ByteArray, pipeline: RadarPipeline, nanos: Long): List<PipelineEvent> {
    BsrecPayloads.readLinkChange(payload)?.let { return pipeline.onLinkState(it, nanos) }
    BsrecPayloads.readModeChange(payload)?.let { pipeline.setEliminated(it == SessionMode.ELIMINATED) }
    return emptyList()
}

private fun replayWatchInput(record: BsrecRecord, pipeline: RadarPipeline, nanos: Long) {
    when (record.type) {
        RecordType.WATCH_GRAVITY -> BsrecPayloads.readGravity(record.payload).let { pipeline.onWatchGravity(it.x, it.y, it.z, it.eventNanos) }
        RecordType.WATCH_GYRO -> BsrecPayloads.readWatchGyro(record.payload).let { pipeline.onWatchGyro(it.x, it.y, it.z, it.eventNanos) }
        RecordType.WATCH_STEP -> pipeline.onWatchStep(BsrecPayloads.readStep(record.payload))
        RecordType.INFO_REREAD -> pipeline.onBeltInfo(BsrecPayloads.readInfo(record.payload), nanos)
        else -> Unit
    }
}

private const val NANOS_PER_MS = 1_000_000L
