package io.github.santiquiroz.blindside.wear.ui.radar

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarmFixPolicyTest {
    private val nowMs = 1_000_000L

    @Test
    fun `a fix younger than twenty seconds makes the warm-up pointless`() {
        assertFalse(shouldWarmFix(lastFixAgeMs = 5_000L, lastFailedAtMs = null, nowMs = nowMs))
        assertFalse(shouldWarmFix(lastFixAgeMs = 19_999L, lastFailedAtMs = null, nowMs = nowMs))
    }

    @Test
    fun `a stale fix with no failed attempt warms the provider`() {
        assertTrue(shouldWarmFix(lastFixAgeMs = 20_000L, lastFailedAtMs = null, nowMs = nowMs))
        assertTrue(shouldWarmFix(lastFixAgeMs = 600_000L, lastFailedAtMs = null, nowMs = nowMs))
    }

    @Test
    fun `a warm-up that timed out less than ninety seconds ago is not repeated`() {
        assertFalse(shouldWarmFix(lastFixAgeMs = 600_000L, lastFailedAtMs = nowMs - 30_000L, nowMs = nowMs))
        assertFalse(shouldWarmFix(lastFixAgeMs = null, lastFailedAtMs = nowMs - 89_999L, nowMs = nowMs))
    }

    @Test
    fun `an old failed attempt no longer holds the warm-up back`() {
        assertTrue(shouldWarmFix(lastFixAgeMs = 600_000L, lastFailedAtMs = nowMs - 90_000L, nowMs = nowMs))
        assertTrue(shouldWarmFix(lastFixAgeMs = null, lastFailedAtMs = nowMs - 300_000L, nowMs = nowMs))
    }

    @Test
    fun `no fix ever and no failure warms the provider`() {
        assertTrue(shouldWarmFix(lastFixAgeMs = null, lastFailedAtMs = null, nowMs = nowMs))
    }

    @Test
    fun `a fresh phone fix skips the warm-up even with no watch fix`() {
        assertFalse(shouldWarmFix(lastFixAgeMs = null, lastFailedAtMs = null, nowMs = nowMs, phoneFixFresh = true))
    }
}
