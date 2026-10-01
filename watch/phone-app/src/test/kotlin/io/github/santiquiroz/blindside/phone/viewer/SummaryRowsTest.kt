package io.github.santiquiroz.blindside.phone.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SummaryRowsTest {
    private val summary = RecordingSummary(
        durationMs = 3_725_000,
        confirmed = 3,
        confirmedPerMinute = 0.6,
        alerts = 2,
        linkGaps = 1,
        linkGapMs = 12_500,
        walkingFraction = 0.25,
        stillFraction = 0.75,
        motionSamples = 4,
        latencyMedianMs = 200,
        latencyP90Ms = 600,
    )

    @Test
    fun `every spec metric reads as one row`() {
        val expected = listOf(
            SummaryRow("Duración", "1:02:05"),
            SummaryRow("Contactos confirmados", "3 · 0,6 por minuto"),
            SummaryRow("Alertas", "2"),
            SummaryRow("Huecos de enlace", "1 · 12,5 s"),
            SummaryRow("Caminando", "25 %"),
            SummaryRow("Quieto", "75 %"),
            SummaryRow("Latencia de confirmación", "mediana 200 ms · p90 600 ms"),
        )
        assertEquals(expected, summaryRows(summary))
    }

    @Test
    fun `missing latency says so`() {
        assertEquals("sin datos", latencyText(summary.copy(latencyMedianMs = null, latencyP90Ms = null)))
    }
}
