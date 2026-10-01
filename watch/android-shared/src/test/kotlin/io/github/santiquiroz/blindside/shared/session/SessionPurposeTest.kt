package io.github.santiquiroz.blindside.shared.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionPurposeTest {
    @Test
    fun `an unknown or missing purpose is a game`() {
        assertEquals(SessionPurpose.GAME, purposeFrom(null))
        assertEquals(SessionPurpose.GAME, purposeFrom("PARTY"))
        assertEquals(SessionPurpose.DIAGNOSTIC, purposeFrom("DIAGNOSTIC"))
    }

    @Test
    fun `asking for the running purpose keeps it, another purpose restarts and nothing running starts`() {
        assertEquals(StartTransition.START, startTransition(null, SessionPurpose.GAME))
        assertEquals(StartTransition.KEEP, startTransition(SessionPurpose.GAME, SessionPurpose.GAME))
        assertEquals(StartTransition.RESTART, startTransition(SessionPurpose.DIAGNOSTIC, SessionPurpose.GAME))
        assertEquals(StartTransition.RESTART, startTransition(SessionPurpose.GAME, SessionPurpose.DIAGNOSTIC))
    }
}
