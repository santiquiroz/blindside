package io.github.santiquiroz.blindside.shared.hud

import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertQueueTest {
    @Test
    fun `three alerts in one tick show one at a time, four seconds each`() {
        var s = AlertQueueState()
        s = enqueueAlert(s, AlertKind.BATTERY_LOW_WATCH)
        s = enqueueAlert(s, AlertKind.BATTERY_LOW_PHONE)
        s = enqueueAlert(s, AlertKind.BELT_LINK_DOWN)
        s = stepAlertQueue(s, 0L)
        assertEquals(AlertKind.BATTERY_LOW_WATCH, s.showing)
        s = stepAlertQueue(s, 3_999L)
        assertEquals(AlertKind.BATTERY_LOW_WATCH, s.showing)
        s = stepAlertQueue(s, 4_000L)
        assertEquals(AlertKind.BATTERY_LOW_PHONE, s.showing)
        s = stepAlertQueue(s, 8_000L)
        assertEquals(AlertKind.BELT_LINK_DOWN, s.showing)
        s = stepAlertQueue(s, 12_000L)
        assertEquals(null, s.showing)
    }

    @Test
    fun `an alert already queued or showing is not duplicated`() {
        var s = enqueueAlert(AlertQueueState(), AlertKind.HYDRATION)
        s = enqueueAlert(s, AlertKind.HYDRATION)
        assertEquals(1, s.pending.size)
        s = stepAlertQueue(s, 0L)
        s = enqueueAlert(s, AlertKind.HYDRATION)
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun `in Sigilo only the critical belt-link-down vibrates`() {
        assertTrue(alertVibrates(AlertKind.BELT_LINK_DOWN, ScreenMode.SIGILO))
        assertFalse(alertVibrates(AlertKind.BATTERY_LOW_WATCH, ScreenMode.SIGILO))
        assertTrue(alertVibrates(AlertKind.BATTERY_LOW_WATCH, ScreenMode.VISTA))
    }

    @Test
    fun `hydration is due first at startup and then every forty-five minutes`() {
        assertTrue(hydrationDue(null, 0L))
        assertFalse(hydrationDue(1_000L, 1_000L + 2_699_999L))
        assertTrue(hydrationDue(1_000L, 1_000L + 2_700_000L))
    }
}
