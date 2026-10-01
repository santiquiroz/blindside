package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.session.SessionUiState

enum class HomeItem { HEADER, START, BLUETOOTH_BLOCKED, START_ERROR, SETTINGS, LAST_RECORDING }

// ScalingLazyColumn centres item 1 on open, so the start button keeps that slot and notices go below it.
fun idleHomeItems(session: SessionUiState, bluetoothBlocked: Boolean): List<HomeItem> = listOfNotNull(
    HomeItem.HEADER,
    HomeItem.START,
    HomeItem.BLUETOOTH_BLOCKED.takeIf { bluetoothBlocked },
    HomeItem.START_ERROR.takeIf { session.startError != null },
    HomeItem.SETTINGS,
    HomeItem.LAST_RECORDING.takeIf { session.lastRecordingName != null },
)
