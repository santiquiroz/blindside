package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tak.Mate
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint

data class RosterEntry(val callsign: String, val point: GeoPoint, val receivedAtMs: Long)

private const val ROSTER_MAX_AGE_MS = 60_000L

data class TeamRoster(val entries: Map<String, RosterEntry> = emptyMap()) {
    fun with(event: CotEvent, ownCallsign: String, nowMs: Long): TeamRoster {
        val updated = if (event.type == "t-x-d-d" && event.linkUid != null) {
            entries - event.linkUid
        } else if (accept(event, ownCallsign, nowMs)) {
            entries + (event.uid to RosterEntry(event.callsign!!, event.point!!, nowMs))
        } else {
            entries
        }
        return TeamRoster(updated.filterValues { isFresh(it, nowMs) })
    }

    fun mates(nowMs: Long): List<Mate> =
        entries.values
            .filter { isFresh(it, nowMs) }
            .map { Mate(it.callsign, it.point, ((nowMs - it.receivedAtMs) / 1_000).toInt()) }
            .sortedBy { it.callsign }
}

private fun isFresh(entry: RosterEntry, nowMs: Long): Boolean =
    nowMs - entry.receivedAtMs <= ROSTER_MAX_AGE_MS

private fun accept(event: CotEvent, ownCallsign: String, nowMs: Long): Boolean {
    // On connect OpenTAKServer replays the last position of players who already left; their stale time has passed.
    if (event.staleMs != null && event.staleMs < nowMs) return false
    val callsign = event.callsign
    val point = event.point
    if (!event.type.startsWith("a-f-")) return false
    if (callsign.isNullOrBlank()) return false
    if (point == null || (point.latDeg == 0.0 && point.lonDeg == 0.0)) return false
    if (event.uid.startsWith("BLINDSIDE-")) return false
    return !callsign.trim().equals(ownCallsign.trim(), ignoreCase = true)
}
