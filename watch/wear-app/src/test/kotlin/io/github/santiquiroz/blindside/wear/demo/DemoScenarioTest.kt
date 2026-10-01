package io.github.santiquiroz.blindside.wear.demo

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoScenarioTest {
    private val packets = demoPackets()

    @Test
    fun `the demo stream lasts about a minute and is in arrival order`() {
        val spanSeconds = (packets.last().arrivalNanos - packets.first().arrivalNanos) / 1e9
        assertTrue(spanSeconds in 55.0..65.0, "span was $spanSeconds s")
        assertEquals(packets.map { it.arrivalNanos }.sorted(), packets.map { it.arrivalNanos })
    }

    @Test
    fun `the demo makes the pipeline vibrate on more than one side`() {
        val pipeline = RadarPipeline(PipelineConfig())
        pipeline.onLinkState(true, packets.first().arrivalNanos)
        val sides = packets
            .flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }
            .filterIsInstance<ContactAlert>()
            .map { it.side }
            .toSet()
        assertTrue(sides.size >= 2, "sides seen: $sides")
    }
}
