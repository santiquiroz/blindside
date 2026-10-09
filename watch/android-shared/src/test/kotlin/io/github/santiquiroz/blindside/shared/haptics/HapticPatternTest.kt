package io.github.santiquiroz.blindside.shared.haptics

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapticPatternTest {
    @Test
    fun `the first pulse separates the centre from the sides`() {
        assertEquals(LONG_PULSE_MS, pulsesMs(CENTER_PATTERN).first())
        assertEquals(SHORT_PULSE_MS, pulsesMs(LEFT_PATTERN).first())
        assertEquals(SHORT_PULSE_MS, pulsesMs(RIGHT_PATTERN).first())
    }

    @Test
    fun `left and right differ only in their second pulse`() {
        assertEquals(listOf(SHORT_PULSE_MS, SHORT_PULSE_MS), pulsesMs(LEFT_PATTERN))
        assertEquals(listOf(SHORT_PULSE_MS, LONG_PULSE_MS), pulsesMs(RIGHT_PATTERN))
    }

    @Test
    fun `the system buzz is clearly longer than the centre pulse`() {
        assertTrue(pulsesMs(SYSTEM_PATTERN).single() >= 2 * pulsesMs(CENTER_PATTERN).single())
    }

    @Test
    fun `gaps are long enough to feel while moving`() {
        assertTrue(PULSE_GAP_MS >= 100L)
        assertTrue(SHORT_PULSE_MS >= 80L)
    }

    @Test
    fun `each side has its own rhythm`() {
        assertEquals(LEFT_PATTERN, patternFor(Side.LEFT))
        assertEquals(CENTER_PATTERN, patternFor(Side.CENTER))
        assertEquals(RIGHT_PATTERN, patternFor(Side.RIGHT))
        assertNotEquals(patternFor(Side.LEFT), patternFor(Side.RIGHT))
    }

    @Test
    fun `contact alerts vibrate their side rhythm`() {
        assertEquals(RIGHT_PATTERN, hapticFor(ContactAlert(displayId = 3, side = Side.RIGHT, tNanos = 0L)))
    }

    @Test
    fun `a far contact alert vibrates its side rhythm at the soft amplitude`() {
        val far = hapticFor(ContactAlert(displayId = 3, side = Side.LEFT, tNanos = 0L, far = true))

        assertEquals(LEFT_PATTERN.timingsMs, far?.timingsMs)
        assertEquals(FAR_AMPLITUDE, far?.amplitude)
        assertEquals(RIGHT_PATTERN.timingsMs, hapticFor(ContactAlert(3, Side.RIGHT, 0L, far = true))?.timingsMs)
        assertEquals(CENTER_PATTERN.timingsMs, hapticFor(ContactAlert(3, Side.CENTER, 0L, far = true))?.timingsMs)
    }

    @Test
    fun `a near contact alert keeps the full amplitude`() {
        val near = hapticFor(ContactAlert(displayId = 3, side = Side.CENTER, tNanos = 0L))

        assertEquals(CENTER_PATTERN, near)
        assertEquals(FULL_AMPLITUDE, near?.amplitude)
        assertEquals(listOf(0, FULL_AMPLITUDE), amplitudesFor(near!!))
    }

    @Test
    fun `every system alert buzzes`() {
        Warning.entries.forEach { kind ->
            assertEquals(SYSTEM_PATTERN, hapticFor(SystemAlert(kind = kind, tNanos = 0L)))
        }
    }

    @Test
    fun `track confirmations do not vibrate`() {
        assertNull(hapticFor(TrackConfirmed(displayId = 3, tNanos = 0L)))
    }

    @Test
    fun `amplitudes are zero on gaps and full on pulses`() {
        assertEquals(listOf(0, FULL_AMPLITUDE, 0, FULL_AMPLITUDE), amplitudesFor(LEFT_PATTERN))
    }

    @Test
    fun `a far pattern pulses at the soft amplitude and stays silent on gaps`() {
        assertEquals(listOf(0, FAR_AMPLITUDE, 0, FAR_AMPLITUDE), amplitudesFor(RIGHT_PATTERN.copy(amplitude = FAR_AMPLITUDE)))
        assertTrue(FAR_AMPLITUDE in 1 until FULL_AMPLITUDE)
    }
}
