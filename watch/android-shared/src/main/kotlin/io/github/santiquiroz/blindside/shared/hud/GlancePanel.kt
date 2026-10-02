package io.github.santiquiroz.blindside.shared.hud

import io.github.santiquiroz.blindside.shared.tactical.tacticalDistanceLabel

const val GLANCE_HOLD_MS = 3_000L

data class GlanceRow(val label: String, val value: String)

data class GlanceData(
    val clockText: String,
    val heartRate: Int?,
    val steps: Int?,
    val distanceM: Double?,
    val watchBattery: Int?,
    val phoneBattery: Int?,
    val beltBattery: Int?,
)

fun glanceVisible(tapAtMs: Long?, nowMs: Long, holdMs: Long = GLANCE_HOLD_MS): Boolean {
    if (tapAtMs == null) return false
    return nowMs - tapAtMs in 0 until holdMs
}

fun glanceRows(data: GlanceData): List<GlanceRow> = listOf(
    GlanceRow("Hora", data.clockText),
    GlanceRow("Pulso", intOrDash(data.heartRate)),
    GlanceRow("Pasos / dist.", "${intOrDash(data.steps)} · ${data.distanceM?.let(::tacticalDistanceLabel) ?: "--"}"),
    GlanceRow("Baterías", "${pct(data.watchBattery)} / ${pct(data.phoneBattery)} / ${pct(data.beltBattery)}"),
)

private fun intOrDash(value: Int?): String = value?.toString() ?: "--"

private fun pct(value: Int?): String = value?.let { "$it%" } ?: "--%"
