package io.github.santiquiroz.blindside.phone.recordings

import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName
import java.io.File

class RecordingsRepository(val dir: File) {
    fun list(): List<LocalRecording> =
        localRecordings(dir.listFiles().orEmpty().filter { it.isFile }.map { FileFacts(it.name, it.length(), it.lastModified()) })

    fun names(): Set<String> = list().map { it.name }.toSet()

    fun file(name: String): File? = name.takeIf(::isRecordingFileName)?.let { File(dir, it) }?.takeIf { it.isFile }

    fun delete(name: String): Boolean = file(name)?.delete() == true
}
