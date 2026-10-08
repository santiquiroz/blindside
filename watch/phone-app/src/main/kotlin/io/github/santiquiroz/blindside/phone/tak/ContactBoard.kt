package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint

const val CONTACT_DEFAULT_LIFE_MS = 30_000L

data class TeamContact(val uid: String, val label: String, val point: GeoPoint, val ageS: Int)

data class BoardEntry(val label: String, val point: GeoPoint, val receivedAtMs: Long, val expiresAtMs: Long)

data class ContactBoard(val entries: Map<String, BoardEntry> = emptyMap()) {
    fun with(event: CotEvent, ownUidPrefix: String, nowMs: Long): ContactBoard {
        val fresh = prune(entries, nowMs)
        if (isDelete(event)) return ContactBoard(fresh - requireNotNull(event.linkUid))
        if (!isAccepted(event, ownUidPrefix, nowMs)) return ContactBoard(fresh)
        return ContactBoard(fresh + (event.uid to boardEntry(event, nowMs)))
    }

    fun contacts(nowMs: Long): List<TeamContact> =
        prune(entries, nowMs).map { (uid, entry) -> teamContact(uid, entry, nowMs) }.sortedBy { it.uid }
}

private fun isDelete(event: CotEvent): Boolean = event.type == "t-x-d-d" && event.linkUid != null

private fun isAccepted(event: CotEvent, ownUidPrefix: String, nowMs: Long): Boolean =
    isTeamType(event.type) && hasFix(event.point) && isFresh(event.staleMs, nowMs) && !event.uid.startsWith(ownUidPrefix)

private fun isTeamType(type: String): Boolean = type.startsWith("a-h-") || type.startsWith("a-u-")

private fun hasFix(point: GeoPoint?): Boolean =
    point != null && (point.latDeg != 0.0 || point.lonDeg != 0.0)

private fun isFresh(staleMs: Long?, nowMs: Long): Boolean = staleMs == null || staleMs >= nowMs

private fun boardEntry(event: CotEvent, nowMs: Long): BoardEntry {
    val point = requireNotNull(event.point)
    val label = event.callsign?.takeIf { it.isNotBlank() } ?: "Contacto"
    return BoardEntry(label, point, nowMs, event.staleMs ?: nowMs + CONTACT_DEFAULT_LIFE_MS)
}

private fun prune(entries: Map<String, BoardEntry>, nowMs: Long): Map<String, BoardEntry> =
    entries.filterValues { it.expiresAtMs >= nowMs }

private fun teamContact(uid: String, entry: BoardEntry, nowMs: Long): TeamContact =
    TeamContact(uid, entry.label, entry.point, ((nowMs - entry.receivedAtMs) / 1000).toInt())
