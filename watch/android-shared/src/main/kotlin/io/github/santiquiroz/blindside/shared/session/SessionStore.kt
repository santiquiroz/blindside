package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

data class SessionUiState(
    val running: Boolean = false,
    val source: SessionSource? = null,
    val ble: BleStatus = BleStatus.IDLE,
    val scene: RadarScene? = null,
    val watchSteps: Boolean = false,
    val recordingName: String? = null,
    val recordingFailed: Boolean = false,
    val lastRecordingName: String? = null,
    val startError: StartError? = null,
    val eliminated: Boolean = false,
    val dndMaySilenceAlerts: Boolean = false,
    val phonePairing: PhonePairing = PhonePairing.IDLE,
    val phonePairingAtMs: Long? = null,
)

object SessionStore {
    private val mutableState = MutableStateFlow(SessionUiState())
    private val mutableRadarVisible = MutableStateFlow(false)
    private val mutableAmbient = MutableStateFlow(false)

    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    val radarVisible: StateFlow<Boolean> = mutableRadarVisible.asStateFlow()
    val ambient: StateFlow<Boolean> = mutableAmbient.asStateFlow()

    fun update(transform: (SessionUiState) -> SessionUiState) = mutableState.update(transform)

    fun toggleEliminated(): Boolean = mutableState.updateAndGet(::eliminatedToggled).eliminated

    fun setRadarVisible(visible: Boolean) {
        mutableRadarVisible.value = visible
    }

    fun setAmbient(ambient: Boolean) {
        mutableAmbient.value = ambient
    }
}

fun startedState(previous: SessionUiState, source: SessionSource): SessionUiState =
    SessionUiState(running = true, source = source, lastRecordingName = previous.lastRecordingName)

fun stoppedState(previous: SessionUiState): SessionUiState =
    SessionUiState(lastRecordingName = previous.recordingName ?: previous.lastRecordingName)

fun blockedState(previous: SessionUiState, error: StartError): SessionUiState =
    stoppedState(previous).copy(startError = error)

fun eliminatedToggled(previous: SessionUiState): SessionUiState = previous.copy(eliminated = !previous.eliminated)

fun activeRecordingName(session: SessionUiState): String? = session.recordingName.takeIf { session.running }
