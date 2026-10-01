package io.github.santiquiroz.blindside.phone.bridge

import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.decodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.decodeWatchStatus
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import java.io.InputStream
import java.io.OutputStream

data class NodeFacts(val id: String, val nearby: Boolean)

sealed interface BridgeResult<out T> {
    data class Ok<out T>(val value: T) : BridgeResult<T>
    data object NoWatch : BridgeResult<Nothing>
    data class Failed(val reason: String) : BridgeResult<Nothing>
}

data class BridgeChange(val path: String, val json: String)

const val BRIDGE_REQUEST_TIMEOUT_MS = 10_000L

// The watch is the node close by; a cloud-relayed node is only a fallback.
fun pickWatchNode(nodes: List<NodeFacts>): String? = (nodes.firstOrNull { it.nearby } ?: nodes.firstOrNull())?.id

// Plan 05 Task 13 publishes /settings and /status as raw UTF-8 JSON (PutDataRequest.setData), never as a key-value map item.
fun jsonFromItemBytes(bytes: ByteArray?): String? = bytes?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)

// The phone and the watch each own an item at the same path; the newest valid one wins and garbage is skipped.
fun newestSettings(jsons: List<String>): SharedSettings? = jsons.mapNotNull(::decodeSharedSettings).maxByOrNull { it.updatedMs }

fun newestStatus(jsons: List<String>): WatchStatus? = jsons.mapNotNull(::decodeWatchStatus).maxByOrNull { it.updatedMs }

const val COPY_BUFFER_BYTES = 64 * 1_024
const val DOWNLOAD_STALL_MS = 15_000L
const val STALL_CHECK_EVERY_MS = 1_000L

enum class DownloadVerdict { COMPLETE, INCOMPLETE, STALLED, CANCELLED }

// A Bluetooth transfer can crawl through 53 MB, so only silence ends it (no byte for 15 s), never its total length.
fun stalled(lastProgressMs: Long, nowMs: Long): Boolean = nowMs - lastProgressMs >= DOWNLOAD_STALL_MS

fun downloadVerdict(copiedBytes: Long, expectedBytes: Long, timedOut: Boolean, cancelled: Boolean): DownloadVerdict = when {
    cancelled -> DownloadVerdict.CANCELLED
    timedOut -> DownloadVerdict.STALLED
    copiedBytes != expectedBytes -> DownloadVerdict.INCOMPLETE
    else -> DownloadVerdict.COMPLETE
}

fun verdictReason(verdict: DownloadVerdict): String = when (verdict) {
    DownloadVerdict.COMPLETE -> "completa"
    DownloadVerdict.INCOMPLETE -> "descarga incompleta"
    DownloadVerdict.STALLED -> "el reloj dejó de enviar datos"
    DownloadVerdict.CANCELLED -> "descarga cancelada"
}

fun copyStream(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit, bufferBytes: Int = COPY_BUFFER_BYTES): Long {
    val buffer = ByteArray(bufferBytes)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return total
        output.write(buffer, 0, read)
        total += read
        onProgress(total)
    }
}
