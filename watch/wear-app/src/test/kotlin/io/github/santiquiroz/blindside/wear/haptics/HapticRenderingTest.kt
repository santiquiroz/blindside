package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.shared.settings.VibrationUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapticRenderingTest {
    @Test
    fun `motors without amplitude control fall back to on-off waveforms`() {
        assertEquals(HapticRenderer.ON_OFF_WAVEFORM, chooseRenderer(hasAmplitudeControl = false))
        assertEquals(HapticRenderer.AMPLITUDE_WAVEFORM, chooseRenderer(hasAmplitudeControl = true))
    }

    @Test
    fun `system interruption filter codes map to filters`() {
        assertEquals(InterruptionFilter.ALL, interruptionFilterFrom(1))
        assertEquals(InterruptionFilter.PRIORITY, interruptionFilterFrom(2))
        assertEquals(InterruptionFilter.NONE, interruptionFilterFrom(3))
        assertEquals(InterruptionFilter.ALARMS, interruptionFilterFrom(4))
        assertEquals(InterruptionFilter.UNKNOWN, interruptionFilterFrom(99))
    }

    @Test
    fun `nothing silences vibrations when do not disturb is off`() {
        assertFalse(dndMaySilence(InterruptionFilter.ALL, VibrationUsage.ALARM))
        assertFalse(dndMaySilence(InterruptionFilter.ALL, VibrationUsage.NOTIFICATION))
    }

    @Test
    fun `alarm usage passes priority and alarms-only modes`() {
        assertFalse(dndMaySilence(InterruptionFilter.PRIORITY, VibrationUsage.ALARM))
        assertFalse(dndMaySilence(InterruptionFilter.ALARMS, VibrationUsage.ALARM))
    }

    @Test
    fun `notification usage is at risk in any do not disturb mode`() {
        assertTrue(dndMaySilence(InterruptionFilter.PRIORITY, VibrationUsage.NOTIFICATION))
        assertTrue(dndMaySilence(InterruptionFilter.ALARMS, VibrationUsage.NOTIFICATION))
    }

    @Test
    fun `total silence and unknown modes always warn`() {
        assertTrue(dndMaySilence(InterruptionFilter.NONE, VibrationUsage.ALARM))
        assertTrue(dndMaySilence(InterruptionFilter.UNKNOWN, VibrationUsage.ALARM))
    }
}
