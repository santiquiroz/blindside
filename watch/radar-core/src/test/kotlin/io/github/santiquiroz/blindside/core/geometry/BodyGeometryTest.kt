package io.github.santiquiroz.blindside.core.geometry

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.defaultMounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BodyGeometryTest {
    private val params = DecodeParams()

    @Test
    fun `bearing is clockwise from straight ahead`() {
        assertEquals(0.0, Point2(0.0, 2.0).bearingDeg, 1e-9)
        assertEquals(90.0, Point2(2.0, 0.0).bearingDeg, 1e-9)
        assertEquals(-45.0, Point2(-1.0, 1.0).bearingDeg, 1e-9)
    }

    @Test
    fun `a radar yawed 90 degrees right sees its boresight on the body right`() {
        val mount = RadarMount(0, 0.0, 0.0, yawDeg = 90.0)

        val body = radarToBody(Point2(0.0, 1.0), mount)

        assertEquals(1.0, body.x, 1e-9)
        assertEquals(0.0, body.y, 1e-9)
    }

    @Test
    fun `radar to body adds the mount position and round trips`() {
        val mount = RadarMount(1, 0.15, 0.0, yawDeg = 20.0)
        val radarPoint = Point2(0.3, 2.5)

        val back = bodyToRadar(radarToBody(radarPoint, mount), mount)

        assertEquals(radarPoint.x, back.x, 1e-9)
        assertEquals(radarPoint.y, back.y, 1e-9)
    }

    @Test
    fun `tracking frame rotates by yaw and back`() {
        val body = Point2(0.0, 3.0)

        val tracking = bodyToTracking(body, yawDeg = 90.0)

        assertEquals(90.0, tracking.bearingDeg, 1e-9)
        assertEquals(0.0, trackingToBody(tracking, 90.0).bearingDeg, 1e-9)
    }

    @Test
    fun `right handed cones cover minus 100 to plus 80 degrees`() {
        val mounts = defaultMounts(Handedness.RIGHT)

        assertEquals(setOf(0), coveringRadars(Point2.fromPolar(3.0, -90.0), mounts, params))
        assertEquals(setOf(0, 1), coveringRadars(Point2.fromPolar(3.0, -10.0), mounts, params))
        assertEquals(setOf(1), coveringRadars(Point2.fromPolar(3.0, 70.0), mounts, params))
        assertTrue(coveringRadars(Point2.fromPolar(3.0, 120.0), mounts, params).isEmpty())
        assertTrue(coveringRadars(Point2.fromPolar(7.0, 0.0), mounts, params).isEmpty())
    }

    @Test
    fun `a margin shrinks the cone`() {
        val mount = RadarMount(0, 0.0, 0.0, yawDeg = 0.0)

        assertTrue(isInCone(Point2.fromPolar(3.0, 55.0), mount, params))
        assertFalse(isInCone(Point2.fromPolar(3.0, 55.0), mount, params, marginDeg = 10.0))
    }

    @Test
    fun `wrapDeg folds into minus 180 to 180`() {
        assertEquals(-170.0, wrapDeg(190.0), 1e-9)
        assertEquals(-180.0, wrapDeg(180.0), 1e-9)
        assertEquals(10.0, wrapDeg(-350.0), 1e-9)
    }
}
