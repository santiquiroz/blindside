package io.github.santiquiroz.blindside.core.scene

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.trackingToBody
import io.github.santiquiroz.blindside.core.geometry.wrapDeg
import io.github.santiquiroz.blindside.core.tracking.Track
import io.github.santiquiroz.blindside.core.tracking.TrackStatus

data class SceneInputs(
    val nowMs: Long,
    val tracks: List<Track>,
    val yawDeg: Double,
    val mounts: List<RadarMount>,
    val radars: List<SensorStatus>,
    val imus: List<SensorStatus>,
    val motion: MotionState,
    val warnings: Set<Warning>,
    val linkUp: Boolean,
    val eliminated: Boolean,
)

fun buildScene(inputs: SceneInputs, tracking: TrackingParams, decode: DecodeParams): RadarScene = RadarScene(
    blips = if (inputs.eliminated) emptyList() else inputs.tracks.filter { it.isDisplayed }.map { blipOf(it, inputs.nowMs, inputs.yawDeg, tracking) },
    coverage = coverageOf(inputs.mounts, inputs.radars.filter { it.alive }.map { it.id }.toSet(), decode),
    linkUp = inputs.linkUp,
    radars = inputs.radars,
    imus = inputs.imus,
    motion = inputs.motion,
    warnings = inputs.warnings,
    eliminated = inputs.eliminated,
)

fun logicalPosition(track: Track, nowMs: Long, yawDeg: Double, tracking: TrackingParams): Point2 =
    trackingToBody(track.predictedTo(nowMs, tracking).kalman.position, yawDeg)

fun blipOf(track: Track, nowMs: Long, yawDeg: Double, tracking: TrackingParams): Blip {
    val position = logicalPosition(track, nowMs, yawDeg, tracking)
    return Blip(
        displayId = track.displayId,
        bearingDeg = wrapDeg(position.bearingDeg),
        rangeM = position.norm,
        confidence = confidenceOf(track),
        ageMs = (nowMs - track.lastHitMs).coerceAtLeast(0),
        outOfView = track.status == TrackStatus.OUT_OF_VIEW,
    )
}

fun confidenceOf(track: Track): Confidence = when {
    track.isLost -> Confidence.COASTING
    track.recentRadars.size >= 2 -> Confidence.BOTH
    else -> Confidence.SINGLE
}

fun coverageOf(mounts: List<RadarMount>, aliveRadars: Set<Int>, decode: DecodeParams): List<CoverageSector> =
    mounts.filter { it.radarId in aliveRadars }
        .map { CoverageSector(it.yawDeg - decode.coneHalfAngleDeg, it.yawDeg + decode.coneHalfAngleDeg) }
