package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.shared.demo.demoPackets
import io.github.santiquiroz.blindside.shared.haptics.HapticPattern
import io.github.santiquiroz.blindside.shared.haptics.hapticFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchPostureTest {
    private val packets = demoPackets()

    private data class DemoRun(val sides: List<Side>, val vibrations: List<HapticPattern>, val scenes: List<RadarScene>)

    private fun runDemo(settings: AppSettings): DemoRun {
        val pipeline = RadarPipeline(toPipelineConfig(settings))
        pipeline.onLinkState(true, packets.first().arrivalNanos)
        val steps = packets.map { pipeline.onBlePacket(it.bytes, it.arrivalNanos) to pipeline.scene(it.arrivalNanos) }
        val events: List<PipelineEvent> = steps.flatMap { it.first }
        return DemoRun(
            sides = events.filterIsInstance<ContactAlert>().map { it.side },
            vibrations = events.mapNotNull(::hapticFor),
            scenes = steps.map { it.second },
        )
    }

    @Test
    fun `the default posture is automatic`() {
        assertEquals(WatchPosture.AUTO, AppSettings().posture)
    }

    @Test
    fun `posture cycles through automatic, normal, tactical left and tactical right`() {
        assertEquals(WatchPosture.NORMAL, nextPosture(WatchPosture.AUTO))
        assertEquals(WatchPosture.TACTICAL_LEFT, nextPosture(WatchPosture.NORMAL))
        assertEquals(WatchPosture.TACTICAL_RIGHT, nextPosture(WatchPosture.TACTICAL_LEFT))
        assertEquals(WatchPosture.AUTO, nextPosture(WatchPosture.TACTICAL_RIGHT))
    }

    @Test
    fun `the tactical postures turn the drawing a quarter turn each way`() {
        assertEquals(0f, WatchPosture.NORMAL.rotationDeg)
        assertEquals(90f, WatchPosture.TACTICAL_LEFT.rotationDeg)
        assertEquals(-90f, WatchPosture.TACTICAL_RIGHT.rotationDeg)
    }

    @Test
    fun `posture never reaches the pipeline config`() {
        val tuned = AppSettings(handedness = Handedness.LEFT).withFlipXToggled(RADAR_A)
        WatchPosture.entries.forEach { posture ->
            assertEquals(toPipelineConfig(tuned), toPipelineConfig(tuned.copy(posture = posture)), "posture $posture")
        }
    }

    @Test
    fun `scenes, alert sides and vibrations stay in the body frame for every posture`() {
        val normal = runDemo(AppSettings())
        assertTrue(normal.sides.toSet().size >= 2, "sides seen: ${normal.sides}")
        WatchPosture.entries.forEach { posture ->
            assertEquals(normal, runDemo(AppSettings(posture = posture)), "posture $posture")
        }
    }
}
