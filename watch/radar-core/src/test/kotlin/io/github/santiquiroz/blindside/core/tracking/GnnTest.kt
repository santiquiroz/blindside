package io.github.santiquiroz.blindside.core.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GnnTest {
    @Test
    fun `global optimum beats greedy nearest neighbour`() {
        val costs = mapOf((0 to 0) to 1.0, (0 to 1) to 2.0, (1 to 0) to 2.0)

        val assignment = assignExact(2, 2, newTrackCost = 9.21) { d, t -> costs[d to t] }

        assertEquals(listOf(1, 0), assignment.trackIndexByDetection)
        assertEquals(4.0, assignment.cost, 1e-12)
    }

    @Test
    fun `a detection outside every gate starts a new track`() {
        val assignment = assignExact(2, 1, newTrackCost = 9.21) { d, _ -> if (d == 0) 0.5 else null }

        assertEquals(listOf(0, null), assignment.trackIndexByDetection)
    }

    @Test
    fun `two detections never share one track`() {
        val assignment = assignExact(2, 1, newTrackCost = 9.21) { _, _ -> 1.0 }

        assertEquals(1, assignment.trackIndexByDetection.count { it == 0 })
    }

    @Test
    fun `no tracks means every detection is new`() {
        val assignment = assignExact(3, 0, newTrackCost = 9.21) { _, _ -> 0.0 }

        assertEquals(listOf(null, null, null), assignment.trackIndexByDetection)
    }
}
