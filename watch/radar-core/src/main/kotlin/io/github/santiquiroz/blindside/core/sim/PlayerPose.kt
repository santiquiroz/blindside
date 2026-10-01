package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.bodyToRadar

data class PlayerPose(val position: Point2, val headingDeg: Double, val yawRateDps: Double, val walking: Boolean)

private val START_POSE = PlayerPose(Point2.ZERO, 0.0, 0.0, walking = false)

fun poseAt(segments: List<PlayerSegment>, tMs: Long): PlayerPose = poseFrom(segments, tMs, START_POSE)

fun worldToBody(world: Point2, pose: PlayerPose): Point2 = (world - pose.position).rotateClockwise(-pose.headingDeg)

fun worldToRadar(world: Point2, pose: PlayerPose, mount: RadarMount): Point2 = bodyToRadar(worldToBody(world, pose), mount)

private tailrec fun poseFrom(remaining: List<PlayerSegment>, elapsedMs: Long, pose: PlayerPose): PlayerPose {
    val segment = remaining.firstOrNull() ?: return pose.copy(yawRateDps = 0.0, walking = false)
    if (elapsedMs < segment.durationMs) return advance(pose, segment, elapsedMs)
    return poseFrom(remaining.drop(1), elapsedMs - segment.durationMs, advance(pose, segment, segment.durationMs))
}

private fun advance(pose: PlayerPose, segment: PlayerSegment, elapsedMs: Long): PlayerPose {
    val dtS = elapsedMs / 1000.0
    return when (segment) {
        is Stand -> pose.copy(yawRateDps = 0.0, walking = false)
        is Turn -> pose.copy(headingDeg = pose.headingDeg + segment.rateDps * dtS, yawRateDps = segment.rateDps, walking = false)
        is Walk -> pose.copy(
            position = pose.position + Point2.fromPolar(segment.speedMps * dtS, pose.headingDeg),
            yawRateDps = 0.0,
            walking = true,
        )
    }
}
