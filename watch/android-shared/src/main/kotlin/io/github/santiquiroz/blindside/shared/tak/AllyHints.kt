package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import io.github.santiquiroz.blindside.shared.compass.shortestTurnDeg
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.bearingDeg
import io.github.santiquiroz.blindside.shared.tactical.distanceM
import kotlin.math.abs

const val TEAM_BEACON_UUID = "6f1b5a2e-8c1d-4f2a-9b3e-5b1d5e7a0c42"
const val ALLY_GPS_RADIUS_M = 15.0
const val ALLY_SECTOR_DEG = 45.0
const val ALLY_MATE_MAX_AGE_S = 15
const val BEACON_NEAR_RSSI_DBM = -70.0
const val BEACON_FRESH_MS = 5_000L
const val BEACON_CONTACT_RANGE_M = 4.0

private const val BEACON_SMOOTHING_ALPHA = 0.3

data class BeaconSeen(val rssiDbm: Double, val lastSeenMs: Long)

fun smoothedBeacon(previous: BeaconSeen?, rssiDbm: Int, nowMs: Long): BeaconSeen {
    if (previous == null) return BeaconSeen(rssiDbm.toDouble(), nowMs)
    return BeaconSeen(BEACON_SMOOTHING_ALPHA * rssiDbm + (1 - BEACON_SMOOTHING_ALPHA) * previous.rssiDbm, nowMs)
}

fun nearBeaconCount(beacons: Map<Long, BeaconSeen>, ownBeacon: Long?, nowMs: Long): Int =
    beacons.count { (id, seen) ->
        id != ownBeacon && seen.rssiDbm >= BEACON_NEAR_RSSI_DBM && nowMs - seen.lastSeenMs in 0..BEACON_FRESH_MS
    }

fun likelyAllyIds(
    blips: List<Blip>,
    mates: List<Mate>,
    here: GeoPoint?,
    bodyHeadingDeg: Double?,
    beacons: Map<Long, BeaconSeen>,
    ownBeacon: Long?,
    nowMs: Long,
): Set<Int> = gpsAllyIds(blips, mates, here, bodyHeadingDeg) + bleAllyIds(blips, beacons, ownBeacon, nowMs)

fun likelyAllyIdsOf(state: SessionUiState, nowMs: Long, nowNanos: Long): Set<Int> {
    val scene = state.scene
    val mates = if (teamLinkActive(state.teamAtMs, nowMs)) state.team?.mates.orEmpty() else emptyList()
    val here = hereOf(state.team, state.teamAtMs, nowMs, null)
    val heading = scene?.let { bodyHeadingDeg(state.headingAnchor, it.bodyYawDeg, it.yawFromBelt, nowNanos) }
    return likelyAllyIds(scene?.blips.orEmpty(), mates, here, heading, state.beacons, state.team?.me, nowMs)
}

private fun gpsAllyIds(blips: List<Blip>, mates: List<Mate>, here: GeoPoint?, bodyHeadingDeg: Double?): Set<Int> {
    if (here == null || bodyHeadingDeg == null) return emptySet()
    val rels = mates
        .filter { it.ageS <= ALLY_MATE_MAX_AGE_S && distanceM(here, it.point) <= ALLY_GPS_RADIUS_M }
        .map { normalizedDeg(bearingDeg(here, it.point) - bodyHeadingDeg) }
    return rels.flatMap { rel -> sectorIds(blips, rel, claimants(rels, rel)) }.toSet()
}

private fun sectorIds(blips: List<Blip>, rel: Double, claimants: Int): List<Int> {
    val sector = blips.filter { withinSector(it.bearingDeg, rel) }.map { it.displayId }
    return if (sector.size <= claimants) sector else emptyList()
}

private fun claimants(rels: List<Double>, rel: Double): Int = rels.count { withinSector(it, rel) }

private fun withinSector(bearingDeg: Double, rel: Double): Boolean =
    abs(shortestTurnDeg(rel, bearingDeg)) <= ALLY_SECTOR_DEG

private fun bleAllyIds(blips: List<Blip>, beacons: Map<Long, BeaconSeen>, ownBeacon: Long?, nowMs: Long): Set<Int> {
    val near = blips.filter { it.rangeM <= BEACON_CONTACT_RANGE_M }.map { it.displayId }
    return if (near.isNotEmpty() && near.size <= nearBeaconCount(beacons, ownBeacon, nowMs)) near.toSet() else emptySet()
}
