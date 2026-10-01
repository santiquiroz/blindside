package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class RecordSinkTest {
    private val marker = BsrecRecord(RecordType.MANUAL_MARKER, 10L, ByteArray(0))

    @Test
    fun `file sink output reads back with the radar-core reader`() {
        val out = ByteArrayOutputStream()
        val sink = BsrecFileSink(out, """{"source":"TEST"}""")
        sink.record(marker)
        sink.record(BsrecRecord(RecordType.WATCH_STEP, 20L, BsrecPayloads.step(42L)))
        sink.close()

        val reader = BsrecReader(ByteArrayInputStream(out.toByteArray()))
        val read = reader.records().toList()
        assertEquals("""{"source":"TEST"}""", reader.headerJson)
        assertEquals(listOf(RecordType.MANUAL_MARKER to 10L, RecordType.WATCH_STEP to 20L), read.map { it.type to it.tMsSinceStart })
        assertEquals(BsrecPayloads.step(42L).toList(), read[1].payload.toList())
    }

    @Test
    fun `a failing sink reports once and the session keeps going`() {
        val failures = mutableListOf<Exception>()
        val sink = SafeRecordSink(ThrowingSink()) { failures += it }
        repeat(3) { sink.record(marker) }
        sink.flush()
        sink.close()
        assertEquals(1, failures.size)
    }

    @Test
    fun `opening a recording where no file can be created falls back to a no-op sink`(@TempDir dir: File) {
        val notADirectory = File(dir, "plain-file").apply { writeText("x") }
        val failures = mutableListOf<Exception>()
        val sink = openRecordingSink(notADirectory, "a.bsrec", "{}") { failures += it }
        sink.record(marker)
        assertSame(NoOpRecordSink, sink)
        assertEquals(1, failures.size)
    }

    @Test
    fun `opening a recording writes the header to disk`(@TempDir dir: File) {
        val sink = openRecordingSink(dir, "a.bsrec", "{}") { error -> throw AssertionError(error) }
        sink.record(marker)
        sink.close()
        assertTrue(File(dir, "a.bsrec").length() > 0)
    }

    @Test
    fun `a belt recording waits for the first info and writes the earlier records after it opens`() {
        val opened = mutableListOf<String?>()
        val inner = MemorySink()
        val sink = InfoHeaderSink { info -> opened += info; inner }
        val gravity = BsrecRecord(RecordType.WATCH_GRAVITY, 5L, BsrecPayloads.gravity(0f, 0f, 9.8f, 1L))
        val info = BsrecRecord(RecordType.INFO_REREAD, 300L, BsrecPayloads.info("""{"boot_id":"ab"}"""))
        sink.record(gravity)
        assertTrue(opened.isEmpty())
        sink.record(info)
        assertEquals(listOf<String?>("""{"boot_id":"ab"}"""), opened)
        assertEquals(listOf(RecordType.WATCH_GRAVITY, RecordType.INFO_REREAD), inner.items.map { it.type })
    }

    @Test
    fun `without belt info the recording opens after ten seconds with no info`() {
        val opened = mutableListOf<String?>()
        val sink = InfoHeaderSink { info -> opened += info; MemorySink() }
        sink.record(BsrecRecord(RecordType.WATCH_STEP, INFO_WAIT_MS - 1, BsrecPayloads.step(1L)))
        assertTrue(opened.isEmpty())
        sink.record(BsrecRecord(RecordType.WATCH_STEP, INFO_WAIT_MS, BsrecPayloads.step(2L)))
        assertEquals(listOf<String?>(null), opened)
    }

    @Test
    fun `closing before any info still writes the file`() {
        val inner = MemorySink()
        val sink = InfoHeaderSink { inner }
        sink.record(marker)
        sink.close()
        assertEquals(1, inner.items.size)
        assertTrue(inner.closed)
    }
}

private class ThrowingSink : RecordSink {
    override fun record(record: BsrecRecord) = throw IOException("disk full")
    override fun flush() = throw IOException("disk full")
    override fun close() = throw IOException("disk full")
}

private class MemorySink : RecordSink {
    val items = mutableListOf<BsrecRecord>()
    var closed = false

    override fun record(record: BsrecRecord) {
        items += record
    }

    override fun flush() = Unit

    override fun close() {
        closed = true
    }
}
