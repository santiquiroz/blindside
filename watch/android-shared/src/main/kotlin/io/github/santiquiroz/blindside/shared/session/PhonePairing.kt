package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BleStatus

enum class PhonePairing { IDLE, REQUESTED, DELIVERED, REFUSED, NO_LINK }

// Spec §2: the belt keeps the window open 60 s (or until the first bond), whoever asked for it.
const val PAIRING_WINDOW_MS = 60_000L
private const val NANOS_PER_MS = 1_000_000L

fun pairingAfterRequest(queued: Boolean): PhonePairing = if (queued) PhonePairing.REQUESTED else PhonePairing.NO_LINK

// The belt answers every control write at the ATT level, so success only means the request arrived (Deviation D8).
fun pairingAfterWrite(delivered: Boolean): PhonePairing = if (delivered) PhonePairing.DELIVERED else PhonePairing.REFUSED

fun recordPairingWrite(session: SessionUiState, delivered: Boolean, nowNanos: Long): SessionUiState =
    session.copy(phonePairing = pairingAfterWrite(delivered), phonePairingAtMs = nowNanos / NANOS_PER_MS)

fun pairingStateAt(state: PhonePairing, deliveredAtMs: Long?, nowMs: Long): PhonePairing =
    if (state == PhonePairing.DELIVERED && isWindowOver(deliveredAtMs, nowMs)) PhonePairing.IDLE else state

// Spec §2: the belt accepts 05 only from a trusted link, so the window can be asked for only while the belt streams to us.
fun canOpenPairingWindow(session: SessionUiState): Boolean =
    session.source == SessionSource.BELT && session.ble == BleStatus.STREAMING

private fun isWindowOver(deliveredAtMs: Long?, nowMs: Long): Boolean =
    deliveredAtMs == null || nowMs - deliveredAtMs >= PAIRING_WINDOW_MS
