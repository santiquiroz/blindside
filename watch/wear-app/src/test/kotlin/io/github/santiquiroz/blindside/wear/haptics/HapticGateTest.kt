package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HapticGateTest {
    private val second = 1_000_000_000L

    @Test
    fun `a contact plays at once when no system buzz is running`() {
        assertEquals(5 * second, contactStartNanos(HapticGate(), 5 * second))
    }

    @Test
    fun `a contact during a system buzz starts when the buzz ends`() {
        val busy = afterSystemBuzz(HapticGate(), 0L)
        assertEquals(SYSTEM_BUZZ_NANOS, contactStartNanos(busy, second / 2))
        assertEquals(2 * second, contactStartNanos(busy, 2 * second))
    }

    @Test
    fun `system alerts are handled before the other events of the same input`() {
        val contact = ContactAlert(1, Side.LEFT, 0L)
        val confirmed = TrackConfirmed(1, 0L)
        val system = SystemAlert(Warning.RADAR_DOWN, 0L)
        assertEquals(listOf(system, contact, confirmed), systemFirst(listOf(contact, system, confirmed)))
    }

    @Test
    fun `waiting times are whole milliseconds and never negative`() {
        assertEquals(700L, millisUntil(atNanos = 1_240_000_000L, nowNanos = 540_000_000L))
        assertEquals(0L, millisUntil(atNanos = 0L, nowNanos = second))
    }
}
