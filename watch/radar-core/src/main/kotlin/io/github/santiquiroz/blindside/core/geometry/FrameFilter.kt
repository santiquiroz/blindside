package io.github.santiquiroz.blindside.core.geometry

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.protocol.Ld2450Codec
import io.github.santiquiroz.blindside.core.protocol.RadarFrame
import io.github.santiquiroz.blindside.core.protocol.RawTarget
import kotlin.math.abs
import kotlin.math.tan

data class Detection(
    val radarId: Int,
    val tMs: Long,
    val radarPoint: Point2,
    val bodyPoint: Point2,
    val radialSpeedMps: Double,
) {
    val radarRangeM: Double get() = radarPoint.norm
    val radarBearingDeg: Double get() = radarPoint.bearingDeg
}

data class SeenTarget(val xMm: Int, val yMm: Int, val speedCms: Int, val repeats: Int)

data class StaleMemory(val previous: List<SeenTarget> = emptyList())

data class FilteredFrame(
    val radarId: Int,
    val tMs: Long,
    val detections: List<Detection>,
    val occupiedSlots: Int,
    val implausible: Int,
    val stale: Int,
    val nearField: Int,
) {
    // Spec §6.5: only an empty (0, 0, 0) slot is free; stale, implausible and excluded targets still occupy theirs.
    val canReportMiss: Boolean get() = occupiedSlots < Ld2450Codec.TARGET_COUNT
}

data class FrameFilterResult(val frame: FilteredFrame, val memory: StaleMemory)

fun isPlausible(target: RawTarget, params: DecodeParams): Boolean =
    target.yMm > 0 &&
        abs(target.xMm) <= target.yMm * tan(Math.toRadians(params.maxAbsAngleDeg)) &&
        abs(target.speedCms) <= params.maxSpeedMps * 100.0 &&
        target.resolutionMm in params.validResolutionsMm

fun markRepeats(targets: List<RawTarget>, memory: StaleMemory): List<SeenTarget> = targets.map { target ->
    val previous = memory.previous.firstOrNull { it.matches(target) }
    SeenTarget(target.xMm, target.yMm, target.speedCms, (previous?.repeats ?: 0) + 1)
}

fun toDetection(target: RawTarget, radarId: Int, tMs: Long, mount: RadarMount): Detection {
    val sign = if (mount.flipX) -1.0 else 1.0
    val radarPoint = Point2(sign * target.xMm / 1000.0, target.yMm / 1000.0)
    return Detection(radarId, tMs, radarPoint, radarToBody(radarPoint, mount), mount.speedSign * target.speedCms / 100.0)
}

fun filterFrame(frame: RadarFrame, mount: RadarMount, memory: StaleMemory, params: DecodeParams): FrameFilterResult {
    val occupied = frame.targets.filterNot { it.isEmpty }
    val plausible = occupied.filter { isPlausible(it, params) }
    val seen = markRepeats(plausible, memory)
    val fresh = plausible.filterIndexed { index, _ -> seen[index].repeats < params.staleRepeatFrames }
    val detections = fresh.map { toDetection(it, frame.radarId, frame.tMs, mount) }
    val kept = detections.filter { it.radarRangeM >= params.nearFieldM }
    val filtered = FilteredFrame(
        radarId = frame.radarId,
        tMs = frame.tMs,
        detections = kept,
        occupiedSlots = occupied.size,
        implausible = occupied.size - plausible.size,
        stale = plausible.size - fresh.size,
        nearField = detections.size - kept.size,
    )
    return FrameFilterResult(filtered, StaleMemory(seen))
}

private fun SeenTarget.matches(target: RawTarget) =
    xMm == target.xMm && yMm == target.yMm && speedCms == target.speedCms
