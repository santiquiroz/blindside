package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.bearingDeg
import io.github.santiquiroz.blindside.shared.tactical.distanceM
import java.util.Locale

const val MATE_MAX_AGE_S = 60
const val MATE_MAX_DISTANCE_M = 1_000.0
const val MATE_MAX_SHOWN = 6
const val SELF_FIX_FRESH_MS = 15_000L

data class MateMark(val label: String, val bearingDeg: Double, val distanceM: Double)

fun teamLinkActive(lastTeamAtMs: Long?, nowMs: Long): Boolean =
    lastTeamAtMs != null && nowMs - lastTeamAtMs in 0..TAK_LINK_FRESH_MS

fun hereOf(team: TeamUpdate?, teamAtMs: Long?, nowMs: Long, watchFix: GeoPoint?): GeoPoint? {
    if (team?.self != null && teamAtMs != null && nowMs - teamAtMs in 0..SELF_FIX_FRESH_MS) return team.self.point
    return watchFix
}

fun mateMarks(mates: List<Mate>, here: GeoPoint): List<MateMark> =
    mates.filter { it.ageS <= MATE_MAX_AGE_S }
        .map { markOf(it, here) }
        .filter { it.distanceM <= MATE_MAX_DISTANCE_M }
        .sortedBy { it.distanceM }
        .take(MATE_MAX_SHOWN)

fun mateLabel(callsign: String): String =
    callsign.filter { it.isLetterOrDigit() }.take(2).uppercase(Locale.ROOT).ifEmpty { "??" }

private fun markOf(mate: Mate, here: GeoPoint): MateMark =
    MateMark(mateLabel(mate.callsign), bearingDeg(here, mate.point), distanceM(here, mate.point))
