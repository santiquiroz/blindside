package io.github.santiquiroz.blindside.shared.tactical

import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latDeg: Double, val lonDeg: Double)

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METRES_PER_KM = 1_000.0

fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latDeg)
    val lat2 = Math.toRadians(to.latDeg)
    val dLon = Math.toRadians(to.lonDeg - from.lonDeg)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return normalizedDeg(Math.toDegrees(atan2(y, x)))
}

fun distanceM(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latDeg)
    val lat2 = Math.toRadians(to.latDeg)
    val dLat = Math.toRadians(to.latDeg - from.latDeg)
    val dLon = Math.toRadians(to.lonDeg - from.lonDeg)
    val a = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun destinationOf(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
    if (distanceM == 0.0) return from
    val lat1 = Math.toRadians(from.latDeg)
    val lon1 = Math.toRadians(from.lonDeg)
    val bearing = Math.toRadians(bearingDeg)
    val angular = distanceM / EARTH_RADIUS_M
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val y = sin(bearing) * sin(angular) * cos(lat1)
    val x = cos(angular) - sin(lat1) * sin(lat2)
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon1 + atan2(y, x)))
}

// The ring turns with north, so a wedge sits at the real bearing minus the watch azimuth, like the compass marks.
fun wedgeScreenAngleDeg(bearingToPointDeg: Double, azimuthDeg: Double): Float =
    normalizedDeg(bearingToPointDeg - azimuthDeg).toFloat()

fun tacticalDistanceLabel(meters: Double): String {
    if (meters < METRES_PER_KM) return String.format(Locale.ROOT, "%03d m", meters.toInt())
    return String.format(Locale.ROOT, "%.1f km", meters / METRES_PER_KM)
}
