package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.pipelineConfigFromHeader
import io.github.santiquiroz.blindside.core.replay.replayRecord
import io.github.santiquiroz.blindside.core.scene.RadarScene
import java.io.Closeable
import java.io.InputStream

internal const val NANOS_PER_MS = 1_000_000L

// The pipeline cannot be rewound, so a seek backwards replays from the start; forward seeks only feed what is new.
// checkpoint runs before each record is taken, so a seek it interrupts loses nothing and the next seek resumes from there.
class ReplayCursor(private val open: () -> InputStream) : Closeable {
    private var stream: InputStream = open()
    private var reader = BsrecReader(stream)
    private var pipeline = startedPipeline(reader.headerJson)
    private var records: Iterator<BsrecRecord> = reader.records().iterator()
    private var pending: BsrecRecord? = null

    var positionMs: Long = 0L
        private set

    fun needsRestart(targetMs: Long): Boolean = targetMs < positionMs

    fun seekTo(targetMs: Long, checkpoint: () -> Unit = {}): RadarScene {
        if (needsRestart(targetMs)) restart()
        feedUntil(targetMs, checkpoint)
        positionMs = targetMs
        return pipeline.scene(targetMs * NANOS_PER_MS)
    }

    override fun close() = stream.close()

    private fun restart() {
        stream.close()
        stream = open()
        reader = BsrecReader(stream)
        pipeline = startedPipeline(reader.headerJson)
        records = reader.records().iterator()
        pending = null
        positionMs = 0L
    }

    private fun feedUntil(targetMs: Long, checkpoint: () -> Unit) {
        while (true) {
            checkpoint()
            val next = pending ?: nextRecord() ?: return
            pending = next
            if (next.tMsSinceStart > targetMs) return
            feed(next)
        }
    }

    private fun feed(record: BsrecRecord) {
        replayRecord(record, pipeline, record.tMsSinceStart * NANOS_PER_MS)
        pending = null
        positionMs = record.tMsSinceStart
    }

    private fun nextRecord(): BsrecRecord? = if (records.hasNext()) records.next() else null
}

// Same start as radar-core's replayRecording: a recording without link records still plays as linked.
private fun startedPipeline(headerJson: String): RadarPipeline =
    RadarPipeline(pipelineConfigFromHeader(headerJson)).also { it.onLinkState(true, 0L) }
