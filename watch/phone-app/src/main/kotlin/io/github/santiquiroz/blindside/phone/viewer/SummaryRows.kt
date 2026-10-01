package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.phone.ui.common.formatClock
import io.github.santiquiroz.blindside.phone.ui.common.formatDecimal
import io.github.santiquiroz.blindside.phone.ui.common.formatPercent
import io.github.santiquiroz.blindside.phone.ui.common.formatSeconds

data class SummaryRow(val label: String, val value: String)

fun summaryRows(summary: RecordingSummary): List<SummaryRow> = listOf(
    SummaryRow("Duración", formatClock(summary.durationMs)),
    SummaryRow("Contactos confirmados", "${summary.confirmed} · ${formatDecimal(summary.confirmedPerMinute)} por minuto"),
    SummaryRow("Alertas", "${summary.alerts}"),
    SummaryRow("Huecos de enlace", "${summary.linkGaps} · ${formatSeconds(summary.linkGapMs)}"),
    SummaryRow("Caminando", formatPercent(summary.walkingFraction)),
    SummaryRow("Quieto", formatPercent(summary.stillFraction)),
    SummaryRow("Latencia de confirmación", latencyText(summary)),
)

fun latencyText(summary: RecordingSummary): String {
    val median = summary.latencyMedianMs ?: return "sin datos"
    return "mediana $median ms · p90 ${summary.latencyP90Ms ?: median} ms"
}
