package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.MAX_BSREC_HEADER_BYTES
import io.github.santiquiroz.blindside.core.replay.pipelineConfigFromHeader
import io.github.santiquiroz.blindside.core.replay.replayRecord
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.coverageOf
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

const val ANALYSIS_SAMPLE_MS = 250L

// Past the link timeout a gap shows no contacts, so sampling all of it would only burn time.
const val MAX_SAMPLED_GAP_MS = 5_000L

private const val UNREADABLE_FILE = "no se pudo leer el archivo"
private const val NOT_A_RECORDING = "no es una grabación .bsrec válida"
private val BOTH_RADARS = setOf(RADAR_A, RADAR_B)

data class RecordingAnalysis(val summary: RecordingSummary, val heat: HeatGrid, val coverage: List<CoverageSector>)

sealed interface AnalysisState {
    data class Running(val progress: Float) : AnalysisState
    data class Done(val analysis: RecordingAnalysis) : AnalysisState
    data class Failed(val reason: String) : AnalysisState
}

class CountingInputStream(inner: InputStream) : FilterInputStream(inner) {
    var count: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

fun analyzeRecording(headerJson: String, records: Sequence<BsrecRecord>, sampleEveryMs: Long = ANALYSIS_SAMPLE_MS): RecordingAnalysis {
    val config = pipelineConfigFromHeader(headerJson)
    val run = AnalysisRun(RadarPipeline(config), sampleEveryMs)
    records.forEach(run::feed)
    return run.finish(coverageOf(config.mounts, BOTH_RADARS, config.tuning.decode))
}

// Any failure inside a damaged file is the file's fault: it ends in Failed, never in a crash of the viewer.
// checkpoint runs before every record, so a cancelled caller stops a 5 h file there instead of at its end.
fun analyzeFile(file: File, checkpoint: () -> Unit = {}, onProgress: (Float) -> Unit): AnalysisState = try {
    CountingInputStream(BufferedInputStream(FileInputStream(file))).use { analyzeStream(it, file.length(), onProgress, checkpoint) }
} catch (error: CancellationException) {
    throw error
} catch (error: IOException) {
    AnalysisState.Failed(UNREADABLE_FILE)
} catch (error: RuntimeException) {
    AnalysisState.Failed(NOT_A_RECORDING)
} catch (error: OutOfMemoryError) {
    AnalysisState.Failed(NOT_A_RECORDING)
} catch (error: StackOverflowError) {
    AnalysisState.Failed(NOT_A_RECORDING)
}

fun countsForAnalysis(scene: RadarScene): Boolean = scene.linkUp && !scene.eliminated

fun percentOf(read: Long, total: Long): Int = (read * 100 / total.coerceAtLeast(1L)).toInt().coerceIn(0, 100)

fun nextSampleAfterSkip(nextSampleMs: Long, recordMs: Long): Long = maxOf(nextSampleMs, recordMs - MAX_SAMPLED_GAP_MS)

// A header can never be longer than the file that holds it.
private fun analyzeStream(counting: CountingInputStream, totalBytes: Long, onProgress: (Float) -> Unit, checkpoint: () -> Unit): AnalysisState {
    val reader = BsrecReader(counting, maxHeaderBytes = minOf(totalBytes, MAX_BSREC_HEADER_BYTES))
    val progress = ProgressReporter(totalBytes, onProgress)
    val records = reader.records().onEach {
        checkpoint()
        progress.report(counting.count)
    }
    return AnalysisState.Done(analyzeRecording(reader.headerJson, records))
}

private class ProgressReporter(private val totalBytes: Long, private val onProgress: (Float) -> Unit) {
    private var lastPercent = -1

    fun report(readBytes: Long) {
        val percent = percentOf(readBytes, totalBytes)
        if (percent <= lastPercent) return
        lastPercent = percent
        onProgress(percent / 100f)
    }
}

private class AnalysisRun(private val pipeline: RadarPipeline, private val sampleEveryMs: Long) {
    private val heat = HeatCounter()
    private var summary = SummaryAccumulator()
    private var nextSampleMs = sampleEveryMs

    init {
        pipeline.onLinkState(true, 0L)
    }

    fun feed(record: BsrecRecord) {
        sampleUntil(record.tMsSinceStart)
        replayRecord(record, pipeline, record.tMsSinceStart * NANOS_PER_MS)
        summary = afterRecord(summary, record)
    }

    fun finish(coverage: List<CoverageSector>): RecordingAnalysis = RecordingAnalysis(summaryOf(summary), heat.toGrid(), coverage)

    private fun sampleUntil(recordMs: Long) {
        nextSampleMs = nextSampleAfterSkip(nextSampleMs, recordMs)
        while (nextSampleMs <= recordMs) {
            sample(nextSampleMs)
            nextSampleMs += sampleEveryMs
        }
    }

    private fun sample(atMs: Long) {
        val scene = pipeline.scene(atMs * NANOS_PER_MS)
        if (!countsForAnalysis(scene)) return
        summary = afterMotion(summary, scene.motion)
        scene.blips.mapNotNull(::heatCellOf).forEach(heat::add)
    }
}
