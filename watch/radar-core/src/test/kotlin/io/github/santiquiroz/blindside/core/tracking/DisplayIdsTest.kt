package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DisplayIdsTest {
    private val params = TrackingParams()
    private val dead = DeadTrack(displayId = 7, position = Point2(0.0, 3.0), velocity = Point2(1.0, 0.0), diedMs = 10_000)

    @Test
    fun `a new track near the prediction of a recently deleted one inherits its id`() {
        val (_, id) = DisplayIds(next = 8, graveyard = listOf(dead)).assign(Point2(1.2, 3.1), 11_000, params)

        assertEquals(7, id)
    }

    @Test
    fun `an inherited id leaves the graveyard`() {
        val (ids, _) = DisplayIds(next = 8, graveyard = listOf(dead)).assign(Point2(1.0, 3.0), 11_000, params)

        assertEquals(emptyList<DeadTrack>(), ids.graveyard)
    }

    @Test
    fun `too far or too old gets a fresh id`() {
        val ids = DisplayIds(next = 8, graveyard = listOf(dead))

        assertEquals(8, ids.assign(Point2(3.0, 3.0), 11_000, params).second)
        assertEquals(8, ids.assign(Point2(2.0, 3.0), 12_100, params).second)
    }

    @Test
    fun `burying prunes entries too old to be inherited`() {
        val track = Track(3, 9, CvKalman.init(Point2(0.0, 3.0), Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04), 1.5), 0, 0, 0)

        val ids = DisplayIds(graveyard = listOf(dead)).bury(track, 12_500, params)

        assertEquals(listOf(9), ids.graveyard.map { it.displayId })
    }

    @Test
    fun `fresh ids increase`() {
        val (first, a) = DisplayIds().assign(Point2(0.0, 3.0), 0, params)
        val (_, b) = first.assign(Point2(5.0, 3.0), 0, params)

        assertEquals(listOf(1, 2), listOf(a, b))
    }
}
