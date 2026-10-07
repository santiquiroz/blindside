package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tak.GeoFix
import io.github.santiquiroz.blindside.shared.tak.Telemetry
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind
import io.github.santiquiroz.blindside.shared.tactical.destinationOf
import kotlin.math.round
import kotlin.math.sin

data class PublishState(val publishedUids: Set<String> = emptySet(), val lastMarkersMs: Long? = null)

data class Outgoing(val state: PublishState, val events: List<String>)

const val CONTACT_TYPE = "a-u-G"
const val FIX_MAX_AGE_MS = 10_000L
const val MARKER_PERIOD_MS = 30_000L

private const val BEAM_HALF_WIDTH_RAD = 15.0 * Math.PI / 180.0

fun publishTelemetry(
    state: PublishState,
    telemetry: Telemetry,
    fix: GeoFix?,
    fixAgeMs: Long?,
    ids: TakIds,
    publishContacts: Boolean,
    nowMs: Long,
): Outgoing {
    val contactsOn = publishContacts && telemetry.headingOk &&
        fix != null && fixAgeMs != null && fixAgeMs <= FIX_MAX_AGE_MS
    val contacts = if (contactsOn) {
        telemetry.blips.map { blip ->
            val uid = ids.contactUid(blip.id)
            val at = destinationOf(fix!!.point, blip.bearingDeg, blip.rangeM)
            uid to contactEvent(uid, "Radar ${ids.callsign} ${blip.id}", at, contactCe(fix.accuracyM, blip.rangeM), nowMs)
        }
    } else {
        emptyList()
    }
    val roundUids = contacts.map { it.first }.toSet()
    val deletes = state.publishedUids.filter { it !in roundUids }.sorted()
        .map { deleteEvent(it, CONTACT_TYPE, nowMs) }
    val markersDue = telemetry.points.isNotEmpty() &&
        (state.lastMarkersMs == null || nowMs - state.lastMarkersMs >= MARKER_PERIOD_MS)
    val markers = if (markersDue) {
        telemetry.points.map { (kind, point) ->
            markerEvent(ids.markerUid(kind), markerCallsign(kind, ids.callsign), point, markerArgb(kind), nowMs)
        }
    } else {
        emptyList()
    }
    val next = PublishState(
        publishedUids = roundUids,
        lastMarkersMs = if (markersDue) nowMs else state.lastMarkersMs,
    )
    return Outgoing(next, contacts.map { it.second } + deletes + markers)
}

private fun contactCe(accuracyM: Double, rangeM: Double): Double =
    maxOf(1.0, round(accuracyM + rangeM * sin(BEAM_HALF_WIDTH_RAD)).toDouble())

private fun markerCallsign(kind: TacticalKind, own: String): String = when (kind) {
    TacticalKind.BASE -> "Base $own"
    TacticalKind.SPAWN -> "Spawn $own"
    TacticalKind.OBJECTIVE -> "Objetivo $own"
}

private fun markerArgb(kind: TacticalKind): Int = when (kind) {
    TacticalKind.BASE -> 0xFF00C853.toInt()
    TacticalKind.SPAWN -> 0xFF00B8D4.toInt()
    TacticalKind.OBJECTIVE -> 0xFFFF1744.toInt()
}
