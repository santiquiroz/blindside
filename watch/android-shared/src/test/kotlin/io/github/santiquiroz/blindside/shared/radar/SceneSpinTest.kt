package io.github.santiquiroz.blindside.shared.radar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SceneSpinTest {
    @Test
    fun `no rotation and no time leaves the spin where it was`() {
        assertEquals(0.0, advanceSceneSpinDeg(0.0, 0.0, 0L), 1e-9)
        assertEquals(10.0, advanceSceneSpinDeg(10.0, 0.0, 0L), 1e-9)
    }

    @Test
    fun `a body turn adds immediate rotation then washes out`() {
        val afterTurn = advanceSceneSpinDeg(0.0, Math.toRadians(90.0), 100L)
        assertTrue(afterTurn > 0.0, "a turn adds spin: $afterTurn")
        val resting = advanceSceneSpinDeg(afterTurn, 0.0, 400L)
        assertTrue(abs(resting) < abs(afterTurn), "spin decays toward zero: $resting")
    }

    @Test
    fun `a huge gap washes the spin fully out and clamps`() {
        assertEquals(0.0, advanceSceneSpinDeg(40.0, 0.0, 10_000L), 1e-6)
        assertTrue(advanceSceneSpinDeg(0.0, Math.toRadians(10_000.0), 100L) <= MAX_SCENE_SPIN_DEG)
    }
}
