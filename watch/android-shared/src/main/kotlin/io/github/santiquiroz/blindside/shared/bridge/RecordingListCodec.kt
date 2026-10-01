package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName

fun encodeRecordingList(entries: List<RecordingEntry>): String = MiniJson.array(entries.map(::encodeEntry))

fun decodeRecordingList(json: String): List<RecordingEntry> =
    (MiniJson.parseOrNull(json) as? List<*>).orEmpty().mapNotNull(::decodeEntry)

private fun encodeEntry(entry: RecordingEntry): String = MiniJson.obj(
    listOf(
        "nombre" to MiniJson.quote(entry.name),
        "bytes" to entry.bytes.toString(),
        "inicio" to entry.startEpochMs.toString(),
    ),
)

// The name becomes a file name on the phone, so anything that is not a recording name is dropped here.
private fun decodeEntry(item: Any?): RecordingEntry? {
    val fields = item as? Map<*, *> ?: return null
    val name = (fields["nombre"] as? String)?.takeIf(::isRecordingFileName) ?: return null
    val bytes = longField(fields, "bytes") ?: return null
    val start = longField(fields, "inicio") ?: return null
    return RecordingEntry(name, bytes, start)
}
