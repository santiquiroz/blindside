package io.github.santiquiroz.blindside.shared.recording

import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.BsrecWriter
import io.github.santiquiroz.blindside.core.replay.RecordType
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val RECORDING_BUFFER_BYTES = 64 * 1024
const val INFO_WAIT_MS = 10_000L
const val MAX_RECORDS_BEFORE_INFO = 2_000

interface RecordSink {
    fun record(record: BsrecRecord)
    fun flush()
    fun close()
}

class BsrecFileSink(private val out: OutputStream, headerJson: String) : RecordSink {
    private val writer = BsrecWriter(out, headerJson)

    override fun record(record: BsrecRecord) = writer.write(record)

    override fun flush() = out.flush()

    override fun close() = writer.close()
}

class SafeRecordSink(
    private val inner: RecordSink,
    private val onFailure: (Exception) -> Unit,
) : RecordSink {
    private var failed = false

    override fun record(record: BsrecRecord) = guarded { inner.record(record) }

    override fun flush() = guarded { inner.flush() }

    override fun close() = guarded { inner.close() }

    // Recording must never stop a game: the first failure is reported and the sink goes quiet.
    private fun guarded(action: () -> Unit) {
        if (failed) return
        try {
            action()
        } catch (error: Exception) {
            failed = true
            onFailure(error)
        }
    }
}

object NoOpRecordSink : RecordSink {
    override fun record(record: BsrecRecord) = Unit
    override fun flush() = Unit
    override fun close() = Unit
}

// Spec §6.10: the header carries the belt's first info, which only exists after the first connection.
class InfoHeaderSink(private val open: (infoJson: String?) -> RecordSink) : RecordSink {
    private var inner: RecordSink? = null
    private val waiting = mutableListOf<BsrecRecord>()

    override fun record(record: BsrecRecord) {
        val target = inner
        if (target != null) target.record(record) else hold(record)
    }

    override fun flush() {
        inner?.flush()
    }

    override fun close() = (inner ?: openWith(null)).close()

    private fun hold(record: BsrecRecord) {
        waiting += record
        val info = infoOf(record)
        if (info != null || stopsWaiting(record)) openWith(info)
    }

    private fun stopsWaiting(record: BsrecRecord): Boolean =
        record.tMsSinceStart >= INFO_WAIT_MS || waiting.size >= MAX_RECORDS_BEFORE_INFO

    private fun openWith(info: String?): RecordSink {
        val opened = open(info)
        waiting.forEach(opened::record)
        waiting.clear()
        inner = opened
        return opened
    }
}

fun infoOf(record: BsrecRecord): String? =
    if (record.type == RecordType.INFO_REREAD) BsrecPayloads.readInfo(record.payload) else null

fun openRecordingSink(dir: File, fileName: String, headerJson: String, onFailure: (Exception) -> Unit): RecordSink =
    try {
        dir.mkdirs()
        val out = BufferedOutputStream(FileOutputStream(File(dir, fileName)), RECORDING_BUFFER_BYTES)
        SafeRecordSink(BsrecFileSink(out, headerJson), onFailure)
    } catch (error: Exception) {
        onFailure(error)
        NoOpRecordSink
    }
