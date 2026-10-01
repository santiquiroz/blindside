package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.radar.CONNECTING_TO_BELT_LABEL
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.wear.ui.bleStatusLabel
import io.github.santiquiroz.blindside.wear.ui.startErrorMessage

private val CONNECTING_STATUSES = setOf(BleStatus.IDLE, BleStatus.CONNECTING, BleStatus.STREAMING)

// The radar opens before the service answers, so a refused start must be explained here and not only on the home.
fun radarMessage(session: SessionUiState): String? =
    session.startError?.let(::startErrorMessage) ?: linkMessage(session.source, session.ble, session.scene?.linkUp == true)

// Searching and pairing keep their own instructions: the first pairing needs the BOOT hint and the passkey prompt.
fun linkMessage(source: SessionSource?, ble: BleStatus, linkUp: Boolean): String? = when {
    source != SessionSource.BELT || linkUp -> null
    ble in CONNECTING_STATUSES -> CONNECTING_TO_BELT_LABEL
    else -> bleStatusLabel(ble)
}
