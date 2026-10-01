package io.github.santiquiroz.blindside.shared.compass

import io.github.santiquiroz.blindside.shared.radar.PointPx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompassRingTest {
    @Test
    fun `ticks every fifteen degrees leave room for the four letters`() {
        val ticks = compassTicks()
        assertEquals(20, ticks.size)
        assertEquals(emptyList<CompassTick>(), ticks.filter { it.angleDeg.toInt() % 90 == 0 })
        assertEquals(listOf(45f, 135f, 225f, 315f), ticks.filter { it.major }.map { it.angleDeg })
    }

    @Test
    fun `the letters are the spanish cardinals`() {
        assertEquals(listOf("N" to 0f, "E" to 90f, "S" to 180f, "O" to 270f), CARDINAL_MARKS.map { it.label to it.angleDeg })
    }

    @Test
    fun `facing east puts north at nine o'clock`() {
        assertEquals(270f, markScreenAngleDeg(0f, 90.0), 1e-4f)
        assertEquals(0f, markScreenAngleDeg(90f, 90.0), 1e-4f)
    }

    @Test
    fun `ring points go clockwise from twelve o'clock`() {
        val center = PointPx(100f, 100f)
        assertEquals(PointPx(100f, 50f), rounded(pointOnRing(center, 50f, 0f)))
        assertEquals(PointPx(150f, 100f), rounded(pointOnRing(center, 50f, 90f)))
        assertEquals(PointPx(50f, 100f), rounded(pointOnRing(center, 50f, 270f)))
    }

    private fun rounded(point: PointPx) = PointPx(Math.round(point.x * 1000f) / 1000f, Math.round(point.y * 1000f) / 1000f)
}
