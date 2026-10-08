package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.DopplerParams
import io.github.santiquiroz.blindside.core.geometry.Detection
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.cos

class EgoMotionTest {
    private val params = DopplerParams()

    @Test
    fun `a known velocity is recovered from four bearings`() {
        val detections = listOf(-40.0, 0.0, 35.0, 60.0).map { detection(it, staticRadial(1.2, it)) }

        val velocity = requireNotNull(EgoMotion().with(detections, 1_000, params).velocity(params))

        assertEquals(0.0, velocity.x, 0.05)
        assertEquals(1.2, velocity.y, 0.05)
    }

    @Test
    fun `an inverted radar sign leaves the velocity unknown`() {
        val bearings = listOf(-40.0, 0.0, 35.0)
        val detections = bearings.map { detection(it, staticRadial(1.0, it), radarId = 0) } +
            bearings.map { detection(it, -staticRadial(1.0, it), radarId = 1) }

        assertNull(EgoMotion().with(detections, 1_000, params).velocity(params))
    }

    @Test
    fun `three detections leave the velocity unknown`() {
        val detections = listOf(-40.0, 0.0, 40.0).map { detection(it, staticRadial(1.0, it)) }

        assertNull(EgoMotion().with(detections, 1_000, params).velocity(params))
    }

    @Test
    fun `a single bearing leaves the velocity unknown`() {
        val detections = (0 until 6).map { detection(0.0, -1.0) }

        assertNull(EgoMotion().with(detections, 1_000, params).velocity(params))
    }

    @Test
    fun `two bearing groups leave the velocity unknown`() {
        val detections = List(3) { detection(7.0, staticRadial(1.0, 7.0)) } +
            List(3) { detection(-32.0, staticRadial(1.0, -32.0)) }

        assertNull(EgoMotion().with(detections, 1_000, params).velocity(params))
    }

    private fun detection(bearingDeg: Double, radialMps: Double, radarId: Int = 0): Detection {
        val point = Point2.fromPolar(3.0, bearingDeg)
        return Detection(radarId, 900, point, point, radialMps)
    }

    private fun staticRadial(speedMps: Double, bearingDeg: Double): Double =
        -speedMps * cos(Math.toRadians(bearingDeg))
}
