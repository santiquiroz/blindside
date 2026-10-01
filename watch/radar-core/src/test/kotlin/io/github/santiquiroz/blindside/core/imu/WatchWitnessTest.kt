package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WatchWitnessTest {
    private val params = ImuParams()
    private val still = WatchWitness((0L..2_000L step 100).map { TimedValue(it, 0.4) })

    @Test
    fun `a still watch covering the window verifies it`() {
        assertEquals(Stillness.VERIFIED, RestEvidence(still).judge(0, 2_000, params))
    }

    @Test
    fun `one watch sample above 3 deg per s means the player moved`() {
        val turned = still.copy(samples = still.samples + TimedValue(1_050, 3.5))

        assertEquals(Stillness.MOVING, RestEvidence(turned).judge(0, 2_000, params))
    }

    @Test
    fun `no watch samples in the window leaves it unverified`() {
        assertEquals(Stillness.UNVERIFIED, RestEvidence().judge(0, 2_000, params))
        assertEquals(Stillness.UNVERIFIED, RestEvidence(still).judge(5_000, 7_000, params))
    }

    @Test
    fun `a step inside the window means the player moved`() {
        assertEquals(Stillness.MOVING, RestEvidence(still, lastStepMs = 1_500).judge(0, 2_000, params))
        assertEquals(Stillness.VERIFIED, RestEvidence(still, lastStepMs = -10).judge(0, 2_000, params))
    }

    @Test
    fun `the witness keeps only the last 5 s`() {
        val long = (0L..10_000L step 100).fold(WatchWitness()) { w, t -> w.withSample(t, 0.1, params) }

        assertEquals(5_000L, long.samples.first().tMs)
    }
}
