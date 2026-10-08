package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryOfTest {
    private val points = mapOf(TacticalKind.BASE to GeoPoint(5.0689, -75.5174))

    private fun sceneWith(blips: List<Blip>) = RadarScene(
        blips = blips,
        coverage = emptyList(),
        linkUp = true,
        radars = emptyList(),
        imus = emptyList(),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )

    @Test
    fun `coasting and out-of-view blips are dropped and bearings rotate to compass`() {
        val scene = sceneWith(
            listOf(
                Blip(1, 30.0, 5.0, Confidence.BOTH, 0L, false),
                Blip(2, 0.0, 5.0, Confidence.COASTING, 0L, false),
                Blip(3, 0.0, 5.0, Confidence.BOTH, 0L, true),
            ),
        )
        val telemetry = telemetryOf(scene, 350.0, points)
        assertTrue(telemetry.headingOk)
        assertEquals(1, telemetry.blips.size)
        assertEquals(20.0, telemetry.blips.single().bearingDeg, 1e-9)
        assertEquals(1, telemetry.blips.single().id)
        assertEquals(points, telemetry.points)
    }

    @Test
    fun `a null heading sends no blips but keeps the points`() {
        val scene = sceneWith(listOf(Blip(1, 30.0, 5.0, Confidence.BOTH, 0L, false)))
        val telemetry = telemetryOf(scene, null, points)
        assertFalse(telemetry.headingOk)
        assertEquals(emptyList<TelemetryBlip>(), telemetry.blips)
        assertEquals(points, telemetry.points)
    }

    @Test
    fun `excluded ids are left out of the published blips`() {
        val scene = sceneWith(
            listOf(
                Blip(1, 30.0, 5.0, Confidence.BOTH, 0L, false),
                Blip(2, 60.0, 6.0, Confidence.BOTH, 0L, false),
            ),
        )
        val telemetry = telemetryOf(scene, 0.0, points, excludeIds = setOf(1))
        assertTrue(telemetry.headingOk)
        assertEquals(listOf(2), telemetry.blips.map { it.id })
    }

    @Test
    fun `a null scene sends no blips`() {
        val telemetry = telemetryOf(null, 350.0, points)
        assertFalse(telemetry.headingOk)
        assertEquals(emptyList<TelemetryBlip>(), telemetry.blips)
        assertEquals(points, telemetry.points)
    }
}
