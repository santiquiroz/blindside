package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tak.GeoFix
import io.github.santiquiroz.blindside.shared.tak.Mate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TeamPicture(val self: GeoFix?, val mates: List<Mate>, val contacts: List<TeamContact>)

data class TakUiState(
    val running: Boolean = false,
    val link: LinkStatus = LinkStatus.Idle,
    val mates: Int = 0,
    val contactsSent: Long = 0,
    val lastFixAtMs: Long? = null,
    val error: String? = null,
    val picture: TeamPicture? = null,
)

object TakStore {
    private val mutableState = MutableStateFlow(TakUiState())

    val state: StateFlow<TakUiState> = mutableState.asStateFlow()

    fun update(transform: (TakUiState) -> TakUiState) = mutableState.update(transform)
}

const val TAK_STATUS_FIX_FRESH_MS = 15_000L

fun takStatusText(state: TakUiState, nowMs: Long): String {
    if (!state.running) return "Desconectado"
    state.error?.let { return it }
    return when (val link = state.link) {
        LinkStatus.Idle, LinkStatus.Connecting -> "Conectando…"
        is LinkStatus.Retrying -> "Reconectando en ${link.inMs / 1_000} s · ${link.reason}"
        is LinkStatus.Connected ->
            if (fixFresh(state.lastFixAtMs, nowMs)) "Conectado · ${state.mates} compañeros · ${state.contactsSent} contactos enviados"
            else "Conectado · sin GPS"
    }
}

// A fix from the future is a clock skew, not stale data.
private fun fixFresh(lastFixAtMs: Long?, nowMs: Long): Boolean =
    lastFixAtMs != null && nowMs - lastFixAtMs <= TAK_STATUS_FIX_FRESH_MS
