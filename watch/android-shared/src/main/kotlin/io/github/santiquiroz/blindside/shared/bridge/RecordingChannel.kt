package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName

fun recordingChannelPath(name: String): String = "$RECORDINGS_GET_PATH/$name"

fun recordingNameFromChannelPath(path: String): String? {
    val prefix = "$RECORDINGS_GET_PATH/"
    if (!path.startsWith(prefix)) return null
    return path.removePrefix(prefix).takeIf(::isRecordingFileName)
}

// Every .bsrec starts with a header, so zero bytes is a refusal even when the close code was lost.
fun downloadWasServed(byteCount: Long, appErrorCode: Int): Boolean =
    byteCount > 0L && appErrorCode != RECORDING_REFUSED_CODE
