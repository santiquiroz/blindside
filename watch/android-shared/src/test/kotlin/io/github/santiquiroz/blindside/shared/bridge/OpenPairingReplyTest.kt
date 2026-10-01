package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenPairingReplyTest {
    @Test
    fun `the reply asks the belt only when its link streams`() {
        val streaming = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.STREAMING)
        assertEquals(OpenPairingReply.REQUESTED, openPairingReplyFor(streaming))
        assertEquals(OpenPairingReply.NO_LINK, openPairingReplyFor(streaming.copy(ble = BleStatus.RECONNECTING)))
        assertEquals(OpenPairingReply.NO_LINK, openPairingReplyFor(SessionUiState()))
    }

    @Test
    fun `replies survive the byte encoding and unknown bytes mean no link`() {
        OpenPairingReply.entries.forEach { assertEquals(it, decodeOpenPairingReply(encodeOpenPairingReply(it))) }
        assertEquals(OpenPairingReply.NO_LINK, decodeOpenPairingReply(byteArrayOf(1, 2, 3)))
    }
}
