package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind

fun telemetryOf(
    scene: RadarScene?,
    headingDeg: Double?,
    points: Map<TacticalKind, GeoPoint>,
    excludeIds: Set<Int> = emptySet(),
): Telemetry {
    if (scene == null || headingDeg == null) return Telemetry(false, emptyList(), points)
    return Telemetry(true, compassBlipsOf(scene, headingDeg, excludeIds), points)
}

private fun compassBlipsOf(scene: RadarScene, headingDeg: Double, excludeIds: Set<Int>): List<TelemetryBlip> =
    scene.blips
        .filter { it.confidence != Confidence.COASTING && !it.outOfView && it.displayId !in excludeIds }
        .map { TelemetryBlip(it.displayId, normalizedDeg(headingDeg + it.bearingDeg), it.rangeM, it.confidence) }
