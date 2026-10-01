package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.replay.BSREC_FORMAT_VERSION
import io.github.santiquiroz.blindside.core.replay.BSREC_MAGIC
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.sim.Scenarios
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import kotlin.math.hypot

class RecordingAnalysisTest {
    private val scenario = Scenarios.crossing()
    private val records = simulatedRecords(scenario)

    private fun scene(linkUp: Boolean, eliminated: Boolean) =
        RadarScene(emptyList(), emptyList(), linkUp, emptyList(), emptyList(), MotionState.STILL, emptySet(), eliminated)

    @Test
    fun `the crossing walker heats cells at its real distance`() {
        val analysis = analyzeRecording(headerFor(scenario), records.asSequence())
        assertTrue(analysis.heat.total > 0)
        val (x, y) = cellCenterM(hottestCell(analysis.heat)!!)
        assertTrue(hypot(x, y) in 2.5..5.0, "hottest cell at ${hypot(x, y)} m")
    }

    @Test
    fun `the summary spans the whole recording`() {
        val summary = analyzeRecording(headerFor(scenario), records.asSequence()).summary
        assertEquals(records.last().tMsSinceStart, summary.durationMs)
        assertTrue(summary.motionSamples > 0)
    }

    @Test
    fun `the fan comes from the recorded mounts`() {
        assertEquals(2, analyzeRecording(headerFor(scenario), records.asSequence()).coverage.size)
    }

    @Test
    fun `a clock jump of weeks does not stall the analysis`() {
        val jumped = listOf(records.first(), BsrecRecord(RecordType.MANUAL_MARKER, 4_000_000_000L, ByteArray(0)))
        assertTimeoutPreemptively(Duration.ofSeconds(5)) { analyzeRecording(headerFor(scenario), jumped.asSequence()) }
    }

    @Test
    fun `a long gap skips straight to the last seconds before the next record`() {
        assertEquals(3_595_000L, nextSampleAfterSkip(250L, 3_600_000L))
        assertEquals(250L, nextSampleAfterSkip(250L, 1_000L))
    }

    @Test
    fun `a file that is not a recording fails with a reason`(@TempDir dir: File) {
        val garbage = File(dir, "x.bsrec").apply { writeText("hello, this is not a recording") }
        assertTrue(analyzeFile(garbage) {} is AnalysisState.Failed)
    }

    @Test
    fun `an empty file fails instead of crashing`(@TempDir dir: File) {
        assertTrue(analyzeFile(File(dir, "e.bsrec").apply { writeBytes(ByteArray(0)) }) {} is AnalysisState.Failed)
    }

    @Test
    fun `a recording cut in the middle of a record still opens`(@TempDir dir: File) {
        val full = recordingBytes(headerFor(scenario), records)
        val cut = File(dir, "cut.bsrec").apply { writeBytes(full.copyOf(full.size - 7)) }
        assertTrue(analyzeFile(cut) {} is AnalysisState.Done)
    }

    @Test
    fun `progress climbs to 100 percent without going back`(@TempDir dir: File) {
        val file = File(dir, "ok.bsrec").apply { writeBytes(recordingBytes(headerFor(scenario), records)) }
        val seen = mutableListOf<Float>()
        analyzeFile(file) { seen += it }
        assertEquals(1f, seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun `a refused checkpoint stops the analysis at that record instead of reading to the end`(@TempDir dir: File) {
        val file = File(dir, "ok.bsrec").apply { writeBytes(recordingBytes(headerFor(scenario), records)) }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            analyzeFile(file, checkpoint = { if (++checks > 10) throw CancellationException("left the Visor") }) {}
        }
        assertEquals(11, checks)
        assertTrue(records.size > checks)
    }

    @Test
    fun `percent is safe with an empty total`() {
        assertEquals(100, percentOf(5, 0))
        assertEquals(50, percentOf(5, 10))
    }

    @Test
    fun `only a linked, playing scene counts`() {
        assertTrue(countsForAnalysis(scene(linkUp = true, eliminated = false)))
        assertFalse(countsForAnalysis(scene(linkUp = false, eliminated = false)))
        assertFalse(countsForAnalysis(scene(linkUp = true, eliminated = true)))
    }

    @Test
    fun `a corrupt header length fails instead of allocating gigabytes`(@TempDir dir: File) {
        val bytes = BSREC_MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(BSREC_FORMAT_VERSION.toByte(), -1, -1, -1, -1) + ByteArray(64)
        assertTrue(analyzeFile(File(dir, "h.bsrec").apply { writeBytes(bytes) }) {} is AnalysisState.Failed)
    }

    @Test
    fun `a zero-filled tail after a valid header still opens without overflowing the stack`(@TempDir dir: File) {
        val bytes = recordingBytes(headerFor(scenario), records) + ByteArray(1_000_000)
        assertTrue(analyzeFile(File(dir, "z.bsrec").apply { writeBytes(bytes) }) {} is AnalysisState.Done)
    }
}
