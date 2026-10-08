package io.github.santiquiroz.blindside.phone.ui.team

import io.github.santiquiroz.blindside.phone.tak.TeamContact
import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.bearingDeg
import io.github.santiquiroz.blindside.shared.tactical.distanceM
import io.github.santiquiroz.blindside.shared.tak.Mate
import io.github.santiquiroz.blindside.shared.tak.mateLabel
import kotlin.math.atan2
import kotlin.math.min

val RANGE_PRESETS_M = listOf(50.0, 100.0, 250.0)

fun nextRange(currentM: Double): Double = when (currentM) {
    50.0 -> 100.0
    100.0 -> 250.0
    250.0 -> 50.0
    else -> 100.0
}

enum class MarkKind { ALLY, CONTACT }

data class RadarMark(
    val kind: MarkKind,
    val label: String,
    val screenAngleDeg: Double,
    val radiusFraction: Double,
    val offScale: Boolean,
    val distanceM: Double,
    val ageS: Int,
)

fun teamMarks(
    here: GeoPoint,
    headingDeg: Double,
    rangeM: Double,
    mates: List<Mate>,
    contacts: List<TeamContact>,
): List<RadarMark> =
    mates.map { allyMark(here, headingDeg, rangeM, it) } +
        contacts.map { contactMark(here, headingDeg, rangeM, it) }

fun contactAlpha(ageS: Int): Float = when {
    ageS <= 5 -> 1.0f
    ageS >= 30 -> 0.3f
    else -> (1.0 - (ageS - 5) * 0.7 / 25.0).toFloat()
}

fun phoneHeadingDeg(rotationMatrix: FloatArray): Double {
    val yEast = rotationMatrix[1].toDouble()
    val yNorth = rotationMatrix[4].toDouble()
    val backEast = -rotationMatrix[2].toDouble()
    val backNorth = -rotationMatrix[5].toDouble()
    // A flat phone aims with its top edge, an upright one with its back: trust the steadier axis.
    return if (axisWeight(yEast, yNorth) >= axisWeight(backEast, backNorth)) {
        axisHeadingDeg(yEast, yNorth)
    } else {
        axisHeadingDeg(backEast, backNorth)
    }
}

private fun allyMark(here: GeoPoint, headingDeg: Double, rangeM: Double, mate: Mate): RadarMark =
    markOf(MarkKind.ALLY, mateLabel(mate.callsign), mate.point, mate.ageS, here, headingDeg, rangeM)

private fun contactMark(here: GeoPoint, headingDeg: Double, rangeM: Double, contact: TeamContact): RadarMark =
    markOf(MarkKind.CONTACT, contact.label, contact.point, contact.ageS, here, headingDeg, rangeM)

private fun markOf(
    kind: MarkKind,
    label: String,
    point: GeoPoint,
    ageS: Int,
    here: GeoPoint,
    headingDeg: Double,
    rangeM: Double,
): RadarMark {
    val distance = distanceM(here, point)
    return RadarMark(
        kind = kind,
        label = label,
        screenAngleDeg = normalizedDeg(bearingDeg(here, point) - headingDeg),
        radiusFraction = min(distance / rangeM, 1.0),
        offScale = distance > rangeM,
        distanceM = distance,
        ageS = ageS,
    )
}

private fun axisWeight(east: Double, north: Double): Double = east * east + north * north

private fun axisHeadingDeg(east: Double, north: Double): Double =
    normalizedDeg(Math.toDegrees(atan2(east, north)))
