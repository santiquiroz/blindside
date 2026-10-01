package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.protocol.buildBytes
import io.github.santiquiroz.blindside.core.protocol.putU16le
import io.github.santiquiroz.blindside.core.protocol.putU32le
import io.github.santiquiroz.blindside.core.protocol.putU8
import io.github.santiquiroz.blindside.core.protocol.u16le
import io.github.santiquiroz.blindside.core.protocol.u32le
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

enum class RecordType(val code: Int) {
    BLE_PACKET(1),
    WATCH_GRAVITY(2),
    WATCH_STEP(3),
    MANUAL_MARKER(4),
    TRACK_CONFIRMED(5),
    VIBRATION_STARTED(6),
    MODE_CHANGE(7),
    INFO_REREAD(8),
    WATCH_GYRO(9),
    RSSI(10),
    ;

    companion object {
        fun fromCode(code: Int): RecordType? = entries.firstOrNull { it.code == code }
    }
}

data class BsrecRecord(val type: RecordType, val tMsSinceStart: Long, val payload: ByteArray)

const val BSREC_MAGIC = "BSREC"
const val BSREC_FORMAT_VERSION = 1
private const val RECORD_HEADER_BYTES = 7

class BsrecWriter(private val out: OutputStream, headerJson: String) {
    init {
        val json = headerJson.toByteArray(Charsets.UTF_8)
        out.write(buildBytes {
            write(BSREC_MAGIC.toByteArray(Charsets.US_ASCII))
            putU8(BSREC_FORMAT_VERSION)
            putU32le(json.size.toLong())
            write(json)
        })
    }

    fun write(record: BsrecRecord) {
        require(record.payload.size <= 0xFFFF) { "payload too large: ${record.payload.size}" }
        out.write(buildBytes {
            putU8(record.type.code)
            putU32le(record.tMsSinceStart and 0xFFFFFFFFL)
            putU16le(record.payload.size)
            write(record.payload)
        })
    }

    fun close() = out.close()
}

const val MAX_BSREC_HEADER_BYTES = 1L shl 20

class BsrecReader(input: InputStream, private val maxHeaderBytes: Long = MAX_BSREC_HEADER_BYTES) {
    private val data = DataInputStream(input)
    val headerJson: String = readHeader()

    // A recording cut by a crash ends with a partial record; reading stops there instead of failing.
    fun records(): Sequence<BsrecRecord> = generateSequence { readRawRecord() }.mapNotNull(::knownRecord)

    private fun readHeader(): String {
        val fixed = ByteArray(BSREC_MAGIC.length + 5).also { data.readFully(it) }
        require(String(fixed, 0, BSREC_MAGIC.length, Charsets.US_ASCII) == BSREC_MAGIC) { "not a .bsrec file" }
        require(fixed[BSREC_MAGIC.length].toInt() == BSREC_FORMAT_VERSION) { "unsupported .bsrec version ${fixed[BSREC_MAGIC.length]}" }
        val length = fixed.u32le(BSREC_MAGIC.length + 1)
        // A corrupt length would allocate gigabytes (or a negative array) before a byte of JSON is read.
        require(length <= maxHeaderBytes) { "header length $length exceeds $maxHeaderBytes" }
        return String(ByteArray(length.toInt()).also { data.readFully(it) }, Charsets.UTF_8)
    }

    // Unknown types are dropped by the sequence, never by recursion: a zero-filled tail used to overflow the stack.
    private fun readRawRecord(): RawRecord? = try {
        val head = ByteArray(RECORD_HEADER_BYTES).also { data.readFully(it) }
        val payload = ByteArray(head.u16le(5)).also { data.readFully(it) }
        RawRecord(head[0].toInt() and 0xFF, head.u32le(1), payload)
    } catch (_: EOFException) {
        null
    }
}

private class RawRecord(val code: Int, val tMs: Long, val payload: ByteArray)

private fun knownRecord(raw: RawRecord): BsrecRecord? =
    RecordType.fromCode(raw.code)?.let { BsrecRecord(it, raw.tMs, raw.payload) }
