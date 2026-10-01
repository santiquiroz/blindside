package io.github.santiquiroz.blindside.core.geometry

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.RadarMount
import kotlin.math.abs

fun RadarMount.position(): Point2 = Point2(xM, yM)

fun radarToBody(radarPoint: Point2, mount: RadarMount): Point2 =
    mount.position() + radarPoint.rotateClockwise(mount.yawDeg)

fun bodyToRadar(bodyPoint: Point2, mount: RadarMount): Point2 =
    (bodyPoint - mount.position()).rotateClockwise(-mount.yawDeg)

fun bodyToTracking(bodyPoint: Point2, yawDeg: Double): Point2 = bodyPoint.rotateClockwise(yawDeg)

fun trackingToBody(trackingPoint: Point2, yawDeg: Double): Point2 = trackingPoint.rotateClockwise(-yawDeg)

fun isInCone(bodyPoint: Point2, mount: RadarMount, params: DecodeParams, marginDeg: Double = 0.0): Boolean {
    val local = bodyToRadar(bodyPoint, mount)
    return local.y > 0 && local.norm <= params.maxRangeM && abs(local.bearingDeg) <= params.coneHalfAngleDeg - marginDeg
}

fun coveringRadars(bodyPoint: Point2, mounts: List<RadarMount>, params: DecodeParams, marginDeg: Double = 0.0): Set<Int> =
    mounts.filter { isInCone(bodyPoint, it, params, marginDeg) }.map { it.radarId }.toSet()
