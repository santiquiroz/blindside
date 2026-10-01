package io.github.santiquiroz.blindside.wear.bridge

import io.github.santiquiroz.blindside.shared.bridge.recordingNameFromChannelPath
import io.github.santiquiroz.blindside.shared.recording.RecordingFile
import java.io.File

fun recordingFilesIn(dir: File): List<RecordingFile> =
    dir.listFiles().orEmpty().filter(File::isFile).map { RecordingFile(it.name, it.length(), it.lastModified()) }

fun servedRecording(dir: File, channelPath: String, activeName: String?): File? {
    val name = recordingNameFromChannelPath(channelPath)?.takeUnless { it == activeName } ?: return null
    return File(dir, name).takeIf(File::isFile)
}
