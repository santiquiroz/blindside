package io.github.santiquiroz.blindside.shared.compass

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class CompassTrust { GOOD, CALIBRATE }

data class CompassReading(val azimuthDeg: Double, val trust: CompassTrust)

const val HEADING_TAU_MS = 150.0
const val CALIBRATE_WARNING = "Brújula: calibra (mueve en 8)"

private const val FULL_TURN_DEG = 360.0
private const val HALF_TURN_DEG = 180.0
private const val CARDINAL_SECTOR_DEG = 45.0
private const val SENSOR_ACCURACY_MEDIUM = 2
private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

fun azimuthFromRotationVector(values: FloatArray): Double {
    val x = values[0].toDouble()
    val y = values[1].toDouble()
    val z = values[2].toDouble()
    val w = if (values.size > 3) values[3].toDouble() else scalarPart(x, y, z)
    // Same as SensorManager.getOrientation()[0]: heading of the 12 o'clock axis in the East-North-Up frame.
    val east = 2.0 * (x * y - z * w)
    val north = 1.0 - 2.0 * (x * x + z * z)
    return normalizedDeg(Math.toDegrees(atan2(east, north)))
}

fun frontHeadingDeg(azimuthDeg: Double, postureRotationDeg: Float): Double =
    normalizedDeg(azimuthDeg + postureRotationDeg)

fun smoothedHeadingDeg(previousDeg: Double?, sampleDeg: Double, dtMs: Long, tauMs: Double = HEADING_TAU_MS): Double {
    if (previousDeg == null) return normalizedDeg(sampleDeg)
    val alpha = 1.0 - exp(-dtMs.coerceAtLeast(0L) / tauMs)
    return normalizedDeg(previousDeg + alpha * shortestTurnDeg(previousDeg, sampleDeg))
}

fun shortestTurnDeg(fromDeg: Double, toDeg: Double): Double {
    val raw = normalizedDeg(toDeg - fromDeg)
    return if (raw > HALF_TURN_DEG) raw - FULL_TURN_DEG else raw
}

fun normalizedDeg(deg: Double): Double {
    val wrapped = deg % FULL_TURN_DEG
    return if (wrapped < 0.0) wrapped + FULL_TURN_DEG else wrapped
}

fun cardinalLabel(headingDeg: Double): String {
    val index = ((normalizedDeg(headingDeg) + CARDINAL_SECTOR_DEG / 2.0) / CARDINAL_SECTOR_DEG).toInt() % CARDINALS.size
    return CARDINALS[index]
}

fun headingText(headingDeg: Double): String {
    val whole = normalizedDeg(headingDeg).roundToInt() % FULL_TURN_DEG.toInt()
    return String.format(Locale.ROOT, "%03d° %s", whole, cardinalLabel(headingDeg))
}

fun compassTrust(accuracy: Int): CompassTrust =
    if (accuracy >= SENSOR_ACCURACY_MEDIUM) CompassTrust.GOOD else CompassTrust.CALIBRATE

fun compassWarningLabel(reading: CompassReading?): String? =
    if (reading?.trust == CompassTrust.CALIBRATE) CALIBRATE_WARNING else null

private fun scalarPart(x: Double, y: Double, z: Double): Double = sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
