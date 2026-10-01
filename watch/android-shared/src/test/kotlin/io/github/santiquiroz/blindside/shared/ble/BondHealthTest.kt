package io.github.santiquiroz.blindside.shared.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BondHealthTest {
    private val failedSetup = ConnectionAttempt(connected = true)
    private val subscribed = ConnectionAttempt(connected = true, subscribed = true)

    private fun after(vararg attempts: ConnectionAttempt): BondHealth =
        attempts.fold(BondHealth()) { health, attempt -> nextBondHealth(health, attemptVerdict(attempt)) }

    @Test
    fun `two failed setups in a row mean the bond is lost`() {
        assertFalse(after(failedSetup).isLost)
        assertTrue(after(failedSetup, failedSetup).isLost)
    }

    @Test
    fun `a subscription in between resets the count`() {
        assertFalse(after(failedSetup, subscribed, failedSetup).isLost)
    }

    @Test
    fun `a disconnect after the stream subscription does not count`() {
        assertEquals(AttemptVerdict.SUBSCRIBED, attemptVerdict(subscribed))
        assertEquals(BondHealth(), nextBondHealth(BondHealth(1), attemptVerdict(subscribed)))
    }

    @Test
    fun `a connection that never came up does not count`() {
        assertEquals(AttemptVerdict.IGNORED, attemptVerdict(ConnectionAttempt()))
        assertFalse(after(failedSetup, ConnectionAttempt(), ConnectionAttempt()).isLost)
    }

    @Test
    fun `a failed setup while pairing is a pairing failure, not a lost bond`() {
        val pairing = ConnectionAttempt(pairing = true, connected = true)
        assertEquals(AttemptVerdict.PAIRING_FAILED, attemptVerdict(pairing))
        assertFalse(after(pairing, pairing).isLost)
    }

    @Test
    fun `a connection the app ended itself does not count`() {
        assertEquals(AttemptVerdict.IGNORED, attemptVerdict(failedSetup.copy(endedByApp = true)))
    }
}
