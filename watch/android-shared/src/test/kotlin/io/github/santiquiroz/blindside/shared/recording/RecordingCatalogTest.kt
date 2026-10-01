package io.github.santiquiroz.blindside.shared.recording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class RecordingCatalogTest {
    private val zone = ZoneId.of("America/Bogota")
    private val startMs = 1_727_790_153_000L

    @Test
    fun `the start comes back from the file name`() {
        assertEquals(startMs, recordingStartFromName(recordingFileName(startMs, zone, "BELT"), zone))
    }

    @Test
    fun `only recording names are recognised`() {
        assertTrue(isRecordingFileName("blindside-belt-20261001-142233.bsrec"))
        assertTrue(isRecordingFileName("blindside-demo-20261001-142233.bsrec"))
        assertFalse(isRecordingFileName("../blindside-belt-20261001-142233.bsrec"))
        assertFalse(isRecordingFileName("blindside-belt-20261001-142233.bsrec.tmp"))
        assertFalse(isRecordingFileName("notes.txt"))
        assertNull(recordingStartFromName("notes.txt", zone))
    }

    @Test
    fun `an impossible stamp falls back to the file time`() {
        val odd = "blindside-belt-20261399-999999.bsrec"
        assertEquals(listOf(RecordingEntry(odd, 10L, 42L)), shareableRecordings(listOf(RecordingFile(odd, 10L, 42L)), null, zone))
    }

    @Test
    fun `the list skips the active recording and other files, newest first`() {
        val older = recordingFileName(startMs, zone, "BELT")
        val newer = recordingFileName(startMs + 60_000L, zone, "DEMO")
        val active = recordingFileName(startMs + 120_000L, zone, "BELT")
        val files = listOf(
            RecordingFile(older, 100L, 0L),
            RecordingFile("notes.txt", 5L, 0L),
            RecordingFile(newer, 200L, 0L),
            RecordingFile(active, 300L, 0L),
        )
        val entries = shareableRecordings(files, activeName = active, zone = zone)
        assertEquals(listOf(newer, older), entries.map { it.name })
        assertEquals(listOf(startMs + 60_000L, startMs), entries.map { it.startEpochMs })
    }
}
