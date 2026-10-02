package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind
import io.github.santiquiroz.blindside.shared.tactical.nextTacticalKind
import io.github.santiquiroz.blindside.shared.tactical.withTacticalPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SessionUiState(
    val running: Boolean = false,
    val source: SessionSource? = null,
    val purpose: SessionPurpose? = null,
    val ble: BleStatus = BleStatus.IDLE,
    val scene: RadarScene? = null,
    val watchSteps: Boolean = false,
    val recordingName: String? = null,
    val recordingFailed: Boolean = false,
    val lastRecordingName: String? = null,
    val startError: StartError? = null,
    val dndMaySilenceAlerts: Boolean = false,
    val phonePairing: PhonePairing = PhonePairing.IDLE,
    val phonePairingAtMs: Long? = null,
    val gameStartElapsedMs: Long? = null,
    val hydrationBaselineMs: Long? = null,
    val tacticalPoints: Map<TacticalKind, GeoPoint> = emptyMap(),
    val lastTacticalKind: TacticalKind? = null,
)

object SessionStore {
    private val mutableState = MutableStateFlow(SessionUiState())
    private val mutableRadarVisible = MutableStateFlow(false)
    private val mutableAmbient = MutableStateFlow(false)

    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    val radarVisible: StateFlow<Boolean> = mutableRadarVisible.asStateFlow()
    val ambient: StateFlow<Boolean> = mutableAmbient.asStateFlow()

    fun update(transform: (SessionUiState) -> SessionUiState) = mutableState.update(transform)

    // A long-press stores the current GPS fix as the next tactical kind; a stop resets the whole state and clears them.
    fun markTactical(at: GeoPoint) = mutableState.update { markedTactical(it, at) }

    // The hydration cadence lives here, not in the HUD, so it survives the screen sleeping in Sigilo (spec §8.3).
    fun markHydrationBaseline(nowMs: Long) = mutableState.update { it.copy(hydrationBaselineMs = nowMs) }

    fun setRadarVisible(visible: Boolean) {
        mutableRadarVisible.value = visible
    }

    fun setAmbient(ambient: Boolean) {
        mutableAmbient.value = ambient
    }
}

fun startedState(
    previous: SessionUiState,
    source: SessionSource,
    purpose: SessionPurpose = SessionPurpose.GAME,
    gameStartElapsedMs: Long? = null,
): SessionUiState =
    SessionUiState(
        running = true,
        source = source,
        purpose = purpose,
        lastRecordingName = previous.lastRecordingName,
        gameStartElapsedMs = gameStartElapsedMs,
    )

// The next kind cycles base → reaparición → objetivo; the first mark with no history starts at base.
fun markedTactical(previous: SessionUiState, at: GeoPoint): SessionUiState {
    val kind = previous.lastTacticalKind?.let(::nextTacticalKind) ?: TacticalKind.BASE
    return previous.copy(
        tacticalPoints = withTacticalPoint(previous.tacticalPoints, kind, at),
        lastTacticalKind = kind,
    )
}

fun stoppedState(previous: SessionUiState): SessionUiState =
    SessionUiState(lastRecordingName = previous.recordingName ?: previous.lastRecordingName)

fun blockedState(previous: SessionUiState, error: StartError): SessionUiState =
    stoppedState(previous).copy(startError = error)

fun activeRecordingName(session: SessionUiState): String? = session.recordingName.takeIf { session.running }
