package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2

data class DeadTrack(val displayId: Int, val position: Point2, val velocity: Point2, val diedMs: Long) {
    fun predictedAt(tMs: Long): Point2 = position + velocity * ((tMs - diedMs) / 1000.0)
}

data class DisplayIds(val next: Int = 1, val graveyard: List<DeadTrack> = emptyList()) {
    fun assign(position: Point2, tMs: Long, params: TrackingParams): Pair<DisplayIds, Int> {
        val recent = graveyard.filter { tMs - it.diedMs < params.inheritMaxAgeMs }
        val heir = recent
            .map { it to (it.predictedAt(tMs) - position).norm }
            .filter { (_, distance) -> distance < params.inheritDistanceM }
            .minByOrNull { (_, distance) -> distance }
            ?.first
        if (heir != null) return copy(graveyard = recent - heir) to heir.displayId
        return DisplayIds(next + 1, recent) to next
    }

    fun bury(track: Track, tMs: Long, params: TrackingParams): DisplayIds {
        val recent = graveyard.filter { tMs - it.diedMs < params.inheritMaxAgeMs }
        return copy(graveyard = recent + DeadTrack(track.displayId, track.kalman.position, track.kalman.velocity, tMs))
    }
}
