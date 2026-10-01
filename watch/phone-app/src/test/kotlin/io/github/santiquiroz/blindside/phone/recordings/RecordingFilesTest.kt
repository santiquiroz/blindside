package io.github.santiquiroz.blindside.phone.recordings

import io.github.santiquiroz.blindside.core.replay.BsrecWriter
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class RecordingFilesTest {
    private val name = "blindside-belt-20261001-153012.bsrec"

    private fun validBsrec(): ByteArray = ByteArrayOutputStream().also { BsrecWriter(it, "{}").close() }.toByteArray()

    @Test
    fun `titles name the device and the start time`() {
        assertEquals("Reloj · 01/10/2026 · 15:30", recordingTitle(name))
        assertEquals("Celular · 01/10/2026 · 09:05", recordingTitle("blindside-phone-20261001-090500.bsrec"))
        assertEquals("Demo · 30/09/2026 · 23:59", recordingTitle("blindside-demo-20260930-235959.bsrec"))
        assertEquals("custom.bsrec", recordingTitle("custom.bsrec"))
    }

    @Test
    fun `the local list keeps only recording names`() {
        val files = listOf(
            FileFacts(name, 10, 1),
            FileFacts("$name.part", 5, 2),
            FileFacts("notes.txt", 1, 3),
            FileFacts("../escape.bsrec", 1, 4),
            FileFacts("custom.bsrec", 1, 5),
        )
        assertEquals(listOf(name), localRecordings(files).map { it.name })
    }

    @Test
    fun `the newest recording comes first`() {
        val files = listOf(
            FileFacts("blindside-belt-20260930-120000.bsrec", 1, modifiedMs = 9_999),
            FileFacts("blindside-phone-20261001-080000.bsrec", 1, modifiedMs = 2),
            FileFacts("blindside-demo-20260930-235959.bsrec", 1, modifiedMs = 1),
        )
        val expected = listOf("blindside-phone-20261001-080000.bsrec", "blindside-demo-20260930-235959.bsrec", "blindside-belt-20260930-120000.bsrec")
        assertEquals(expected, localRecordings(files).map { it.name })
    }

    @Test
    fun `remote rows mark what is already here, drop unsafe names and put the newest first`() {
        val old = RecordingEntry("blindside-belt-20261001-100000.bsrec", 1, 100)
        val new = RecordingEntry("blindside-belt-20261001-110000.bsrec", 2, 200)
        val rows = remoteRows(listOf(old, new, RecordingEntry("../x.bsrec", 3, 300)), localNames = setOf(old.name))
        assertEquals(listOf(new.name to false, old.name to true), rows.map { it.entry.name to it.alreadyLocal })
        assertEquals(listOf(true, true), markDownloaded(rows, new.name).map { it.alreadyLocal })
    }

    @Test
    fun `the recording being written can only be watched growing`() {
        assertEquals(RowActions(canOpen = false, canShare = false, canDelete = false, note = RECORDING_NOW_NOTE), rowActions(name, activeName = name))
        assertEquals(RowActions(canOpen = true, canShare = true, canDelete = true, note = null), rowActions(name, activeName = "other.bsrec"))
        assertEquals(RowActions(canOpen = true, canShare = true, canDelete = true, note = null), rowActions(name, activeName = null))
    }

    @Test
    fun `a complete download becomes the recording and leaves no part file`(@TempDir dir: File) {
        val bytes = validBsrec()
        val part = File(dir, partFileName(name)).apply { writeBytes(bytes) }
        val target = File(dir, name)
        assertTrue(finishDownload(part, target, expectedBytes = bytes.size.toLong()))
        assertTrue(target.isFile)
        assertFalse(part.exists())
    }

    @Test
    fun `a cut download is discarded and never replaces an existing recording`(@TempDir dir: File) {
        val part = File(dir, partFileName(name)).apply { writeBytes(byteArrayOf(0x42, 0x53)) }
        val target = File(dir, name).apply { writeBytes(validBsrec()) }
        assertFalse(finishDownload(part, target, expectedBytes = 2))
        assertFalse(part.exists())
        assertTrue(looksLikeBsrec(target))
    }

    @Test
    fun `a part with a valid header but fewer bytes than listed is discarded`(@TempDir dir: File) {
        val headerOnly = validBsrec()
        val part = File(dir, partFileName(name)).apply { writeBytes(headerOnly) }
        assertFalse(finishDownload(part, File(dir, name), expectedBytes = headerOnly.size + 4_096L))
        assertFalse(part.exists())
        assertFalse(File(dir, name).exists())
    }

    @Test
    fun `an empty or missing file is not a recording`(@TempDir dir: File) {
        assertFalse(looksLikeBsrec(File(dir, "empty.bsrec").apply { writeBytes(ByteArray(0)) }))
        assertFalse(looksLikeBsrec(File(dir, "missing.bsrec")))
    }
}
