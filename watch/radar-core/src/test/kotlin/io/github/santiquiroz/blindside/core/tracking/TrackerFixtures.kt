package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Detection
import io.github.santiquiroz.blindside.core.geometry.FilteredFrame
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.bodyToRadar

val FORWARD_RADAR = listOf(RadarMount(0, 0.0, 0.0, yawDeg = 0.0))

fun testContext(
    mounts: List<RadarMount> = FORWARD_RADAR,
    motion: (Long) -> MotionContext = { MotionContext.STILL },
) = TrackerContext(mounts, mounts.map { it.radarId }.toSet(), { 0.0 }, motion)

fun frameOf(
    radarId: Int,
    tMs: Long,
    points: List<Point2>,
    speedMps: Double = -0.5,
    mounts: List<RadarMount> = FORWARD_RADAR,
    occupiedSlots: Int = points.size,
): FilteredFrame {
    val mount = mounts.first { it.radarId == radarId }
    val detections = points.map { Detection(radarId, tMs, bodyToRadar(it, mount), it, speedMps) }
    return FilteredFrame(radarId, tMs, detections, occupiedSlots, implausible = 0, stale = 0, nearField = 0)
}

data class Replayed(val states: List<TrackerState>, val confirmations: List<Pair<Long, Track>>) {
    val last: TrackerState get() = states.last()
}

fun replay(frames: List<FilteredFrame>, ctx: TrackerContext, tuning: TuningParams = TuningParams()): Replayed {
    val start = Replayed(listOf(TrackerState()), emptyList())
    return frames.fold(start) { acc, frame ->
        val step = Tracker.onFrame(acc.last, frame, ctx, tuning)
        Replayed(acc.states + step.state, acc.confirmations + step.confirmed.map { frame.tMs to it })
    }
}

fun walkingTarget(fromMs: Long, frames: Int, start: Point2, velocity: Point2, radarId: Int = 0, phaseMs: Long = 5): List<FilteredFrame> =
    (0 until frames).map { k ->
        val tMs = fromMs + phaseMs + k * 100L
        frameOf(radarId, tMs, listOf(start + velocity * (k * 0.1)))
    }

fun emptyFrames(fromMs: Long, frames: Int, radarId: Int = 0, phaseMs: Long = 5, mounts: List<RadarMount> = FORWARD_RADAR): List<FilteredFrame> =
    (0 until frames).map { k -> frameOf(radarId, fromMs + phaseMs + k * 100L, emptyList(), mounts = mounts) }
