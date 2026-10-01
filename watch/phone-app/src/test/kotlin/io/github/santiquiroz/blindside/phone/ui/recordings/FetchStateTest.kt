package io.github.santiquiroz.blindside.phone.ui.recordings

import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.recordings.RemoteRow
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FetchStateTest {
    private val here = RecordingEntry("blindside-belt-20261001-100000.bsrec", 10, 2)
    private val there = RecordingEntry("blindside-belt-20261001-110000.bsrec", 20, 3)
    private val rows = listOf(RemoteRow(there, false), RemoteRow(here, true))

    @Test
    fun `a listed reply marks what is already on the phone`() {
        assertEquals(FetchState.Listed(rows), listedState(BridgeResult.Ok(listOf(here, there)), setOf(here.name)))
    }

    @Test
    fun `an empty watch says so`() {
        assertEquals(FetchState.Listed(emptyList(), NO_REMOTE_RECORDINGS), listedState(BridgeResult.Ok(emptyList()), emptySet()))
    }

    @Test
    fun `no watch or no answer ends in a message`() {
        assertEquals(FetchState.Problem(NO_WATCH_TEXT), listedState(BridgeResult.NoWatch, emptySet()))
        val failed = listedState(BridgeResult.Failed("sin respuesta a tiempo"), emptySet())
        assertTrue(failed is FetchState.Problem && "sin respuesta a tiempo" in failed.text)
    }

    @Test
    fun `a finished download marks its row and says so`() {
        val state = afterDownload(rows, there.name, BridgeResult.Ok(File(there.name)))
        assertEquals(listOf(true, true), currentRows(state).map { it.alreadyLocal })
        assertTrue((state as FetchState.Listed).note!!.startsWith("Traída"))
    }

    @Test
    fun `a failed download keeps the rows and explains why`() {
        val state = afterDownload(rows, there.name, BridgeResult.Failed("descarga incompleta"))
        assertEquals(rows, currentRows(state))
        assertTrue("descarga incompleta" in (state as FetchState.Listed).note!!)
    }

    @Test
    fun `progress stays between zero and one`() {
        assertEquals(0.5f, downloadProgress(5, 10))
        assertEquals(1f, downloadProgress(20, 10))
        assertEquals(0f, downloadProgress(5, 0))
    }

    @Test
    fun `the dialog cannot be dismissed in the middle of a download`() {
        assertFalse(canDismiss(FetchState.Downloading(rows, there.name, 1, 20)))
        assertTrue(canDismiss(FetchState.Loading))
    }

    @Test
    fun `a download can always be cancelled and nothing else offers cancel`() {
        assertTrue(canCancel(FetchState.Downloading(rows, there.name, 1, 20)))
        assertFalse(canCancel(FetchState.Listed(rows)))
        assertFalse(canCancel(FetchState.Loading))
    }
}
