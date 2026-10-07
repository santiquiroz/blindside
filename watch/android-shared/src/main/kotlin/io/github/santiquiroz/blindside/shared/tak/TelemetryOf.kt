package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind

fun telemetryOf(scene: RadarScene?, headingDeg: Double?, points: Map<TacticalKind, GeoPoint>): Telemetry {
    if (scene == null || headingDeg == null) return Telemetry(false, emptyList(), points)
    return Telemetry(true, compassBlipsOf(scene, headingDeg), points)
}

private fun compassBlipsOf(scene: RadarScene, headingDeg: Double): List<TelemetryBlip> =
    scene.blips
        .filter { it.confidence != Confidence.COASTING && !it.outOfView }
        .map { TelemetryBlip(it.displayId, normalizedDeg(headingDeg + it.bearingDeg), it.rangeM, it.confidence) }
