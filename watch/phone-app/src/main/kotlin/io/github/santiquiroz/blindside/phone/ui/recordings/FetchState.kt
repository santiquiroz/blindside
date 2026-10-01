package io.github.santiquiroz.blindside.phone.ui.recordings

import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.recordings.RemoteRow
import io.github.santiquiroz.blindside.phone.recordings.markDownloaded
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.recordings.remoteRows
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import java.io.File

sealed interface FetchState {
    data object Loading : FetchState
    data class Listed(val rows: List<RemoteRow>, val note: String? = null) : FetchState
    data class Downloading(val rows: List<RemoteRow>, val name: String, val receivedBytes: Long, val totalBytes: Long) : FetchState
    data class Problem(val text: String) : FetchState
}

const val NO_WATCH_TEXT = "No hay un reloj conectado. Revisa la conexión en Galaxy Wearable y vuelve a intentarlo."
const val NO_REMOTE_RECORDINGS = "El reloj no tiene grabaciones terminadas."

fun listedState(result: BridgeResult<List<RecordingEntry>>, localNames: Set<String>): FetchState = when (result) {
    is BridgeResult.Ok -> FetchState.Listed(remoteRows(result.value, localNames), if (result.value.isEmpty()) NO_REMOTE_RECORDINGS else null)
    BridgeResult.NoWatch -> FetchState.Problem(NO_WATCH_TEXT)
    is BridgeResult.Failed -> FetchState.Problem("El reloj no respondió (${result.reason}). Actualiza Blindside en el reloj e inténtalo otra vez.")
}

fun afterDownload(rows: List<RemoteRow>, name: String, result: BridgeResult<File>): FetchState = when (result) {
    is BridgeResult.Ok -> FetchState.Listed(markDownloaded(rows, name), "Traída: ${recordingTitle(name)}")
    BridgeResult.NoWatch -> FetchState.Listed(rows, "El reloj se desconectó: no se trajo ${recordingTitle(name)}.")
    is BridgeResult.Failed -> FetchState.Listed(rows, "No se pudo traer ${recordingTitle(name)} (${result.reason}).")
}

fun currentRows(state: FetchState): List<RemoteRow> = when (state) {
    is FetchState.Listed -> state.rows
    is FetchState.Downloading -> state.rows
    else -> emptyList()
}

fun downloadProgress(received: Long, total: Long): Float = if (total <= 0L) 0f else (received.toFloat() / total).coerceIn(0f, 1f)

fun canDismiss(state: FetchState): Boolean = state !is FetchState.Downloading

fun canCancel(state: FetchState): Boolean = state is FetchState.Downloading
