package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import java.time.Instant
import java.util.Locale

fun cotTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

fun xmlAttr(value: String): String = buildString(value.length) {
    for (char in value) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(char)
        }
    }
}

fun identityEvent(uid: String, callsign: String, appVersion: String, nowMs: Long): String =
    eventHeader(uid, "a-f-G-E-S", "h-g-i-g-o", nowMs, nowMs + 5_000) + ZERO_POINT +
        "<detail><contact callsign=\"${xmlAttr(callsign)}\"/>" +
        "<takv device=\"Blindside\" platform=\"Blindside\" os=\"Android\"" +
        " version=\"${xmlAttr(appVersion)}\"/></detail></event>"

fun contactEvent(uid: String, callsign: String, at: GeoPoint, ceM: Double, nowMs: Long): String =
    eventHeader(uid, "a-u-G", "m-g", nowMs, nowMs + 10_000) +
        pointTag(at.latDeg, at.lonDeg, formatCe(ceM)) +
        "<detail><contact callsign=\"${xmlAttr(callsign)}\"/>" +
        "<remarks>Blindside: contacto de radar, posición aproximada</remarks></detail></event>"

fun deleteEvent(targetUid: String, targetType: String, nowMs: Long): String =
    eventHeader("$targetUid-delete", "t-x-d-d", "h-g-i-g-o", nowMs, nowMs + 20_000) + ZERO_POINT +
        "<detail><link uid=\"${xmlAttr(targetUid)}\" relation=\"none\" type=\"${xmlAttr(targetType)}\"/>" +
        "<__forcedelete/></detail></event>"

fun markerEvent(uid: String, callsign: String, at: GeoPoint, argb: Int, nowMs: Long): String =
    eventHeader(uid, "b-m-p-s-m", "h-g-i-g-o", nowMs, nowMs + 600_000) +
        pointTag(at.latDeg, at.lonDeg, "9999999.0") +
        "<detail><contact callsign=\"${xmlAttr(callsign)}\"/><color argb=\"$argb\"/>" +
        "<remarks>Blindside: punto táctico</remarks></detail></event>"

fun pingEvent(uid: String, nowMs: Long): String =
    eventHeader(uid, "t-x-c-t", "h-g-i-g-o", nowMs, nowMs + 20_000) + ZERO_POINT + "<detail/></event>"

private const val ZERO_POINT =
    "<point lat=\"0.0\" lon=\"0.0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"

private fun eventHeader(uid: String, type: String, how: String, nowMs: Long, staleMs: Long): String {
    val time = cotTime(nowMs)
    return "<event version=\"2.0\" uid=\"${xmlAttr(uid)}\" type=\"$type\" how=\"$how\"" +
        " time=\"$time\" start=\"$time\" stale=\"${cotTime(staleMs)}\">"
}

private fun pointTag(latDeg: Double, lonDeg: Double, ce: String): String =
    "<point lat=\"${formatCoord(latDeg)}\" lon=\"${formatCoord(lonDeg)}\"" +
        " hae=\"9999999.0\" ce=\"$ce\" le=\"9999999.0\"/>"

private fun formatCoord(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

private fun formatCe(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
