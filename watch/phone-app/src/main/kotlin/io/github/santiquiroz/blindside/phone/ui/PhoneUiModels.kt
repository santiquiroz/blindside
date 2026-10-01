package io.github.santiquiroz.blindside.phone.ui

import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.PhonePrefs
import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.SettingsTransform

data class PhoneUiState(
    val session: SessionUiState = SessionUiState(),
    val phone: PhoneDiagnostics = PhoneDiagnostics(),
    val settings: AppSettings = AppSettings(),
    val prefs: PhonePrefs = PhonePrefs(),
    val watchStatus: WatchStatus? = null,
    val bluetoothBlocked: Boolean = false,
)

data class PhoneActions(
    val startRadar: () -> Unit = {},
    val startDiagnostic: () -> Unit = {},
    val stop: () -> Unit = {},
    val toggleEliminated: () -> Unit = {},
    val retryLink: () -> Unit = {},
    val sendCommand: (BeltCommand) -> Unit = {},
    val refreshInfo: () -> Unit = {},
    val updateSettings: (SettingsTransform) -> Unit = {},
    val updatePrefs: ((PhonePrefs) -> PhonePrefs) -> Unit = {},
    val openAppSettings: () -> Unit = {},
)
