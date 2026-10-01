package io.github.santiquiroz.blindside.phone.recordings

import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class RecordingOrigin { WATCH, PHONE, DEMO, UNKNOWN }

data class FileFacts(val name: String, val bytes: Long, val modifiedMs: Long)

data class LocalRecording(
    val name: String,
    val bytes: Long,
    val modifiedMs: Long,
    val origin: RecordingOrigin,
    val startedAt: LocalDateTime?,
)

data class RemoteRow(val entry: RecordingEntry, val alreadyLocal: Boolean)

data class RowActions(val canOpen: Boolean, val canShare: Boolean, val canDelete: Boolean, val note: String?)

const val BSREC_MIME = "application/octet-stream"
const val RECORDING_NOW_NOTE = "Grabando…"

private const val PART_SUFFIX = ".part"
private val NAME_PATTERN = Regex("^blindside-([a-z]+)-(\\d{8})-(\\d{6})\\.bsrec$")
private val NAME_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
private val TITLE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm")
private val NEWEST_FIRST: Comparator<LocalRecording> =
    compareByDescending<LocalRecording> { it.startedAt }.thenByDescending { it.modifiedMs }
private val IDLE_ROW = RowActions(canOpen = true, canShare = true, canDelete = true, note = null)
private val GROWING_ROW = RowActions(canOpen = false, canShare = false, canDelete = false, note = RECORDING_NOW_NOTE)

fun originOfName(name: String): RecordingOrigin = when (NAME_PATTERN.matchEntire(name)?.groupValues?.get(1)) {
    "belt" -> RecordingOrigin.WATCH
    "phone" -> RecordingOrigin.PHONE
    "demo" -> RecordingOrigin.DEMO
    else -> RecordingOrigin.UNKNOWN
}

fun startedAtOf(name: String): LocalDateTime? {
    val match = NAME_PATTERN.matchEntire(name) ?: return null
    return runCatching { LocalDateTime.parse(match.groupValues[2] + match.groupValues[3], NAME_STAMP) }.getOrNull()
}

fun originLabel(origin: RecordingOrigin): String = when (origin) {
    RecordingOrigin.WATCH -> "Reloj"
    RecordingOrigin.PHONE -> "Celular"
    RecordingOrigin.DEMO -> "Demo"
    RecordingOrigin.UNKNOWN -> "Otra"
}

fun recordingTitle(name: String): String =
    startedAtOf(name)?.let { "${originLabel(originOfName(name))} · ${TITLE_STAMP.format(it)}" } ?: name

fun localRecordings(files: List<FileFacts>): List<LocalRecording> =
    files.filter { isRecordingFileName(it.name) }.map(::localRecording).sortedWith(NEWEST_FIRST)

fun remoteRows(entries: List<RecordingEntry>, localNames: Set<String>): List<RemoteRow> =
    entries.filter { isRecordingFileName(it.name) }.sortedByDescending { it.startEpochMs }.map { RemoteRow(it, it.name in localNames) }

fun markDownloaded(rows: List<RemoteRow>, name: String): List<RemoteRow> =
    rows.map { if (it.entry.name == name) it.copy(alreadyLocal = true) else it }

// The file being written is still growing: opening it reads a moving target, and sharing or deleting it pulls it from under the recorder.
fun rowActions(name: String, activeName: String?): RowActions = if (name == activeName) GROWING_ROW else IDLE_ROW

fun partFileName(name: String): String = name + PART_SUFFIX

fun looksLikeBsrec(file: File): Boolean = runCatching { file.inputStream().use { BsrecReader(it).headerJson } }.isSuccess

// A part becomes a recording only with every listed byte and a readable header, so a cut transfer never reaches the list.
fun finishDownload(part: File, target: File, expectedBytes: Long): Boolean {
    if (part.length() != expectedBytes || !looksLikeBsrec(part)) return discard(part)
    target.delete()
    return part.renameTo(target)
}

private fun localRecording(file: FileFacts): LocalRecording =
    LocalRecording(file.name, file.bytes, file.modifiedMs, originOfName(file.name), startedAtOf(file.name))

private fun discard(part: File): Boolean {
    part.delete()
    return false
}
