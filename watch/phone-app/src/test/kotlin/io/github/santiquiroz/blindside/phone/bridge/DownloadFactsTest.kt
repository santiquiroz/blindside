package io.github.santiquiroz.blindside.phone.bridge

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DownloadFactsTest {
    @Test
    fun `a transfer stalls only after 15 s without a byte, however long it is`() {
        assertFalse(stalled(lastProgressMs = 1_000L, nowMs = 15_999L))
        assertTrue(stalled(lastProgressMs = 1_000L, nowMs = 16_000L))
    }

    @Test
    fun `copying reports growing progress and copies every byte`() {
        val source = ByteArray(200_000) { (it % 251).toByte() }
        val target = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copied = copyStream(ByteArrayInputStream(source), target, onProgress = { progress += it })
        assertEquals(200_000L, copied)
        assertArrayEquals(source, target.toByteArray())
        assertEquals(200_000L, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `only every listed byte with no stall and no cancel completes a download`() {
        assertEquals(DownloadVerdict.COMPLETE, downloadVerdict(copiedBytes = 10, expectedBytes = 10, timedOut = false, cancelled = false))
        assertEquals(DownloadVerdict.INCOMPLETE, downloadVerdict(copiedBytes = 9, expectedBytes = 10, timedOut = false, cancelled = false))
        assertEquals(DownloadVerdict.STALLED, downloadVerdict(copiedBytes = 10, expectedBytes = 10, timedOut = true, cancelled = false))
        assertEquals(DownloadVerdict.CANCELLED, downloadVerdict(copiedBytes = 3, expectedBytes = 10, timedOut = true, cancelled = true))
    }

    @Test
    fun `every failed verdict explains itself`() {
        DownloadVerdict.entries.filter { it != DownloadVerdict.COMPLETE }.forEach { assertTrue(verdictReason(it).isNotBlank()) }
    }
}
