package io.github.santiquiroz.blindside.wear.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.santiquiroz.blindside.shared.session.PhonePairing
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.pairingStateAt
import kotlinx.coroutines.delay

private const val PAIRING_TICK_MS = 1_000L

// The belt closes the window on its own after 60 s, so the chip re-reads the clock instead of trusting the stored state.
@Composable
fun rememberPhonePairing(session: SessionUiState): PhonePairing {
    val state by produceState(session.phonePairing, session.phonePairing, session.phonePairingAtMs) {
        while (true) {
            value = pairingStateAt(session.phonePairing, session.phonePairingAtMs, SystemClock.elapsedRealtime())
            delay(PAIRING_TICK_MS)
        }
    }
    return state
}
