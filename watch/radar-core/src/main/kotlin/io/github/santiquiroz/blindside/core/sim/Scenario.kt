package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.defaultMounts
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.imu.Vec3

sealed interface PlayerSegment {
    val durationMs: Long
}

data class Stand(override val durationMs: Long) : PlayerSegment

data class Turn(override val durationMs: Long, val rateDps: Double) : PlayerSegment

data class Walk(override val durationMs: Long, val speedMps: Double) : PlayerSegment

data class Waypoint(val tMs: Long, val x: Double, val y: Double)

data class SimTarget(val waypoints: List<Waypoint>) {
    init {
        require(waypoints.size >= 2) { "a target needs at least two waypoints" }
    }

    fun positionAt(tMs: Long): Point2? {
        if (tMs < waypoints.first().tMs || tMs > waypoints.last().tMs) return null
        val after = waypoints.first { it.tMs >= tMs }
        val before = waypoints.last { it.tMs <= tMs }
        if (after.tMs == before.tMs) return Point2(before.x, before.y)
        val f = (tMs - before.tMs).toDouble() / (after.tMs - before.tMs)
        return Point2(before.x + (after.x - before.x) * f, before.y + (after.y - before.y) * f)
    }
}

data class Scenario(
    val name: String,
    val player: List<PlayerSegment>,
    val targets: List<SimTarget> = emptyList(),
    val mounts: List<RadarMount> = defaultMounts(Handedness.RIGHT),
    val gyroBiasDps: List<Vec3> = listOf(Vec3(1.5, -0.8, 0.6), Vec3(-1.0, 0.5, -0.7)),
    val bleDelayMs: Long = 20,
    val radarLatencyMs: Long = 100,
    val imuPhaseOffsetMs: List<Long> = listOf(0, 7),
    val watchGyroPeriodMs: Long? = 100,
    val droppedSeqs: Set<Int> = emptySet(),
    val radarDownFromMs: Map<Int, Long> = emptyMap(),
) {
    val durationMs: Long get() = player.sumOf { it.durationMs }
}

fun walker(fromMs: Long, toMs: Long, start: Point2, velocityMps: Point2): SimTarget {
    val end = start + velocityMps * ((toMs - fromMs) / 1000.0)
    return SimTarget(listOf(Waypoint(fromMs, start.x, start.y), Waypoint(toMs, end.x, end.y)))
}

fun stillObject(fromMs: Long, toMs: Long, at: Point2): SimTarget =
    SimTarget(listOf(Waypoint(fromMs, at.x, at.y), Waypoint(toMs, at.x, at.y)))

fun marcher(fromMs: Long, toMs: Long, center: Point2, amplitudeM: Double = 0.15, halfPeriodMs: Long = 500): SimTarget {
    val steps = ((toMs - fromMs) / halfPeriodMs).toInt()
    val waypoints = (0..steps).map { i ->
        val offset = if (i % 2 == 0) -amplitudeM else amplitudeM
        Waypoint(fromMs + i * halfPeriodMs, center.x, center.y + offset)
    }
    return SimTarget(waypoints)
}
