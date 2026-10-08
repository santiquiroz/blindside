package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.compass.cardinalLabel
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.bearingDeg
import io.github.santiquiroz.blindside.shared.tactical.distanceM
import kotlin.math.roundToInt

const val PROXIMITY_ALERT_M = 30.0
const val ALERT_REPEAT_MS = 30_000L

private const val ALERT_BOOK_LIFE_MS = 300_000L

data class ProximityAlert(val uid: String, val label: String, val distanceM: Double, val bearingDeg: Double)

data class AlertBook(val lastAlertMs: Map<String, Long> = emptyMap())

data class AlertRound(val book: AlertBook, val alerts: List<ProximityAlert>)

fun dueAlerts(book: AlertBook, contacts: List<TeamContact>, here: GeoPoint?, nowMs: Long): AlertRound {
    if (here == null) return AlertRound(book, emptyList())
    val alerts = contacts.mapNotNull { dueAlert(book, it, here, nowMs) }.sortedBy { it.distanceM }
    val updated = pruneBook(book.lastAlertMs + alerts.associate { it.uid to nowMs }, nowMs)
    return AlertRound(AlertBook(updated), alerts)
}

fun alertText(alert: ProximityAlert): String =
    "Contacto a ${alert.distanceM.roundToInt()} m al ${cardinalLabel(alert.bearingDeg)} · ${alert.label}"

private fun dueAlert(book: AlertBook, contact: TeamContact, here: GeoPoint, nowMs: Long): ProximityAlert? {
    val distance = distanceM(here, contact.point)
    if (distance > PROXIMITY_ALERT_M) return null
    val last = book.lastAlertMs[contact.uid]
    if (last != null && nowMs - last < ALERT_REPEAT_MS) return null
    return ProximityAlert(contact.uid, contact.label, distance, bearingDeg(here, contact.point))
}

private fun pruneBook(lastAlertMs: Map<String, Long>, nowMs: Long): Map<String, Long> =
    lastAlertMs.filterValues { nowMs - it < ALERT_BOOK_LIFE_MS }
