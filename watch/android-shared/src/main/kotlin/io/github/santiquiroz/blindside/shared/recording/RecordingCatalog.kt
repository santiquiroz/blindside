package io.github.santiquiroz.blindside.shared.recording

import java.time.ZoneId

data class RecordingFile(val name: String, val bytes: Long, val lastModifiedMs: Long)

data class RecordingEntry(val name: String, val bytes: Long, val startEpochMs: Long)

fun shareableRecordings(files: List<RecordingFile>, activeName: String?, zone: ZoneId): List<RecordingEntry> =
    files.filter { isShareable(it, activeName) }.map { entryFor(it, zone) }.sortedByDescending { it.startEpochMs }

// The file being written has no header end yet; offering it would hand the phone a truncated recording.
private fun isShareable(file: RecordingFile, activeName: String?): Boolean =
    isRecordingFileName(file.name) && file.name != activeName

private fun entryFor(file: RecordingFile, zone: ZoneId): RecordingEntry =
    RecordingEntry(file.name, file.bytes, recordingStartFromName(file.name, zone) ?: file.lastModifiedMs)
