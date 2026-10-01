package io.github.santiquiroz.blindside.wear.bridge

import io.github.santiquiroz.blindside.shared.bridge.recordingChannelPath
import io.github.santiquiroz.blindside.shared.recording.RecordingFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RecordingServingTest {
    private val name = "blindside-belt-20261001-142233.bsrec"

    @Test
    fun `an existing recording is served`(@TempDir dir: File) {
        File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(File(dir, name), servedRecording(dir, recordingChannelPath(name), activeName = null))
    }

    @Test
    fun `the active, missing or unsafe recording is not served`(@TempDir dir: File) {
        File(dir, name).writeBytes(byteArrayOf(1))
        assertNull(servedRecording(dir, recordingChannelPath(name), activeName = name))
        assertNull(servedRecording(dir, recordingChannelPath("blindside-belt-20261001-000000.bsrec"), activeName = null))
        assertNull(servedRecording(dir, "/recordings/get/../$name", activeName = null))
    }

    @Test
    fun `recording files are listed with their size and time`(@TempDir dir: File) {
        val file = File(dir, name).apply { writeBytes(ByteArray(5)) }
        File(dir, "sub").mkdir()
        assertEquals(listOf(RecordingFile(name, 5L, file.lastModified())), recordingFilesIn(dir))
    }
}
