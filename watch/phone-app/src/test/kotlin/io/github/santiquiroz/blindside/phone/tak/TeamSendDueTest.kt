package io.github.santiquiroz.blindside.phone.tak

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamSendDueTest {
    @Test
    fun `first send is always due`() {
        assertTrue(teamSendDue(0, 0.0, null))
    }

    @Test
    fun `mates present keep sending`() {
        assertTrue(teamSendDue(1, 0.0, 2_000))
    }

    @Test
    fun `unknown movement sends`() {
        assertTrue(teamSendDue(0, null, 2_000))
    }

    @Test
    fun `movement of one meter sends`() {
        assertTrue(teamSendDue(0, 1.0, 2_000))
    }

    @Test
    fun `five seconds since last send sends`() {
        assertTrue(teamSendDue(0, 0.2, 5_000))
    }

    @Test
    fun `alone and still within five seconds skips`() {
        assertFalse(teamSendDue(0, 0.2, 4_000))
    }
}
