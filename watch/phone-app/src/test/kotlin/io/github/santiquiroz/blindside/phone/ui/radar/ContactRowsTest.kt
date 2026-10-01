package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.radar.BlipStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContactRowsTest {
    private fun blip(id: Int, rangeM: Double, bearingDeg: Double = 0.0, confidence: Confidence = Confidence.BOTH, ageMs: Long = 0, outOfView: Boolean = false) =
        Blip(id, bearingDeg, rangeM, confidence, ageMs, outOfView)

    private fun scene(blips: List<Blip>, linkUp: Boolean = true, eliminated: Boolean = false) =
        RadarScene(blips, emptyList(), linkUp, emptyList(), emptyList(), MotionState.STILL, emptySet(), eliminated)

    @Test
    fun `contacts are listed nearest first`() {
        val rows = contactRows(scene(listOf(blip(1, 4.0), blip(2, 1.5), blip(3, 2.5))))
        assertEquals(listOf("#2", "#3", "#1"), rows.map { it.id })
    }

    @Test
    fun `a row shows distance, bearing, confidence and age in readable units`() {
        val row = contactRow(blip(4, 2.44, bearingDeg = -44.6, confidence = Confidence.SINGLE, ageMs = 1_200))
        assertEquals(ContactRow("#4", "2,4 m", "-45°", "Un radar", "1,2 s", BlipStyle.OUTLINE), row)
    }

    @Test
    fun `confidence is told by shape as well as by words`() {
        assertEquals("Ambos radares" to BlipStyle.FILLED, contactRow(blip(1, 1.0)).let { it.confidence to it.style })
        assertEquals("Perdido" to BlipStyle.DASHED, contactRow(blip(1, 1.0, confidence = Confidence.COASTING)).let { it.confidence to it.style })
    }

    @Test
    fun `a contact out of view says so`() {
        assertEquals("Fuera de vista", contactRow(blip(1, 1.0, outOfView = true)).confidence)
    }

    @Test
    fun `bearings are signed and zero has no sign`() {
        assertEquals("+30°", formatBearing(30.2))
        assertEquals("0°", formatBearing(-0.4))
        assertEquals("0°", formatBearing(0.4))
    }

    @Test
    fun `no contacts are listed without a link, when eliminated or without a scene`() {
        val blips = listOf(blip(1, 1.0))
        assertEquals(emptyList<ContactRow>(), contactRows(scene(blips, linkUp = false)))
        assertEquals(emptyList<ContactRow>(), contactRows(scene(blips, eliminated = true)))
        assertEquals(emptyList<ContactRow>(), contactRows(null))
    }
}
