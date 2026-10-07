package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.TacticalKind

data class TakIds(val deviceId: String, val callsign: String) {
    val identityUid: String get() = "BLINDSIDE-$deviceId"
    val pingUid: String get() = "BLINDSIDE-$deviceId-ping"
    fun contactUid(id: Int): String = "BLINDSIDE-$deviceId-C$id"
    fun markerUid(kind: TacticalKind): String = "BLINDSIDE-$deviceId-${kind.name}"
}

// OpenTAKServer drops a known callsign that shows up under a new uid, so the uid must survive reinstalls: it comes from the certificate.
fun deviceIdOf(clientName: String): String =
    clientName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.ifEmpty { "player" }
