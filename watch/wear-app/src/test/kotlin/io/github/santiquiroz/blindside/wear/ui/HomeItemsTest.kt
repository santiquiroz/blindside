package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeItemsTest {
    @Test
    fun `the start button is the second item so the list centres it`() {
        assertEquals(HomeItem.START, idleHomeItems(SessionUiState(), bluetoothBlocked = false)[1])
        val blocked = SessionUiState(startError = StartError.BLUETOOTH_UNAVAILABLE)
        assertEquals(HomeItem.START, idleHomeItems(blocked, bluetoothBlocked = true)[1])
    }

    @Test
    fun `notices sit between the start button and the settings chip`() {
        val items = idleHomeItems(SessionUiState(startError = StartError.BLUETOOTH_UNAVAILABLE), bluetoothBlocked = true)
        assertEquals(listOf(HomeItem.HEADER, HomeItem.START, HomeItem.BLUETOOTH_BLOCKED, HomeItem.START_ERROR, HomeItem.SETTINGS), items)
    }

    @Test
    fun `the last recording closes the list`() {
        val items = idleHomeItems(SessionUiState(lastRecordingName = "x.bsrec"), bluetoothBlocked = false)
        assertEquals(listOf(HomeItem.HEADER, HomeItem.START, HomeItem.SETTINGS, HomeItem.LAST_RECORDING), items)
    }
}
