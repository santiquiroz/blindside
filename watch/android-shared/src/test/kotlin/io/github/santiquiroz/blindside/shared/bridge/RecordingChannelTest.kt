package io.github.santiquiroz.blindside.shared.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingChannelTest {
    private val name = "blindside-belt-20261001-142233.bsrec"

    @Test
    fun `a recording name round-trips through the channel path`() {
        assertEquals("/recordings/get/$name", recordingChannelPath(name))
        assertEquals(name, recordingNameFromChannelPath(recordingChannelPath(name)))
    }

    @Test
    fun `paths outside the prefix, nested or traversing are refused`() {
        assertNull(recordingNameFromChannelPath("/recordings/list/$name"))
        assertNull(recordingNameFromChannelPath("/recordings/get/../$name"))
        assertNull(recordingNameFromChannelPath("/recordings/get/x/$name"))
        assertNull(recordingNameFromChannelPath("/recordings/get/"))
        assertNull(recordingNameFromChannelPath("/recordings/get$name"))
    }

    @Test
    fun `a refused or empty download is not a recording`() {
        assertTrue(downloadWasServed(byteCount = 1_024L, appErrorCode = 0))
        assertFalse(downloadWasServed(byteCount = 0L, appErrorCode = 0))
        assertFalse(downloadWasServed(byteCount = 0L, appErrorCode = RECORDING_REFUSED_CODE))
        assertFalse(downloadWasServed(byteCount = 1_024L, appErrorCode = RECORDING_REFUSED_CODE))
    }
}
