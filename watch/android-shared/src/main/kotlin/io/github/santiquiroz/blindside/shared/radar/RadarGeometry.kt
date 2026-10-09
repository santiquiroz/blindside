package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.RadarScene
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class PointPx(val x: Float, val y: Float)

enum class BlipStyle { FILLED, OUTLINE, DASHED }

data class BlipDraw(val center: PointPx, val style: BlipStyle, val alpha: Float, val id: Int = 0, val farLabel: String? = null)

data class EdgeMarkerDraw(val inner: PointPx, val outer: PointPx, val alpha: Float)

data class SectorDraw(val startAngleDeg: Float, val sweepDeg: Float)

data class RadarDrawModel(
    val origin: PointPx,
    val radiusPx: Float,
    val blipRadiusPx: Float,
    val ringRadiiPx: List<Float>,
    val sectors: List<SectorDraw>,
    val blips: List<BlipDraw>,
    val edgeMarkers: List<EdgeMarkerDraw>,
    val dimmed: Boolean,
)

const val DISPLAY_RANGE_M = 6.0
const val BLIP_RADIUS_FRACTION = 0.035f
const val MIN_BLIP_ALPHA = 0.3f

private const val FULL_FADE_MS = 6_000.0
private const val EDGE_MARKER_INNER_M = 5.4
private const val CANVAS_ZERO_OFFSET_DEG = 90.0
private val RING_RANGES_M = listOf(2.0, 4.0)

fun toDrawModel(
    scene: RadarScene?,
    widthPx: Float,
    heightPx: Float,
    offset: PointPx,
    showContacts: Boolean,
    edgeMarginPx: Float = 0f,
    fitHalfAngleDeg: Double = MAX_FIT_HALF_ANGLE_DEG,
): RadarDrawModel {
    val side = min(widthPx, heightPx)
    val fit = fitFan(side, edgeMarginPx, fitHalfAngleDeg)
    val origin = PointPx(widthPx / 2f + offset.x, heightPx / 2f + fit.originYOffsetPx + offset.y)
    val radius = fit.radiusPx
    val blips = if (showContacts) scene?.blips.orEmpty() else emptyList()
    return RadarDrawModel(
        origin = origin,
        radiusPx = radius,
        blipRadiusPx = side * BLIP_RADIUS_FRACTION,
        ringRadiiPx = RING_RANGES_M.map { (it / DISPLAY_RANGE_M).toFloat() * radius },
        sectors = scene?.coverage.orEmpty().map(::sectorArc),
        blips = blips.filterNot { it.outOfView }.map { blipDraw(it, origin, radius) },
        edgeMarkers = blips.filter { it.outOfView }.map { edgeMarker(it, origin, radius) },
        dimmed = !showContacts,
    )
}

data class RangeMark(val meters: Int, val at: PointPx)

// The metre scale along the front axis (bearing 0): the two rings plus the outer edge, before posture rotation.
fun rangeMarks(origin: PointPx, radiusPx: Float): List<RangeMark> =
    (RING_RANGES_M + DISPLAY_RANGE_M).map { RangeMark(it.toInt(), polarToPx(origin, radiusPx, 0.0, it)) }

fun showContacts(scene: RadarScene?, ambient: Boolean): Boolean =
    scene != null && scene.linkUp && !scene.eliminated && !ambient

fun polarToPx(origin: PointPx, radiusPx: Float, bearingDeg: Double, rangeM: Double): PointPx {
    val distance = rangeM.coerceIn(0.0, DISPLAY_RANGE_M) / DISPLAY_RANGE_M * radiusPx
    val radians = Math.toRadians(bearingDeg)
    return PointPx(origin.x + (distance * sin(radians)).toFloat(), origin.y - (distance * cos(radians)).toFloat())
}

fun sectorArc(sector: CoverageSector): SectorDraw =
    SectorDraw((sector.fromDeg - CANVAS_ZERO_OFFSET_DEG).toFloat(), sweepDeg(sector).toFloat())

fun blipStyle(confidence: Confidence): BlipStyle = when (confidence) {
    Confidence.BOTH -> BlipStyle.FILLED
    Confidence.SINGLE -> BlipStyle.OUTLINE
    Confidence.COASTING -> BlipStyle.DASHED
}

enum class ContactTone { FULL, DIM }

// Spec §6: accent-dim marks contacts kept alive without a fresh measurement.
fun contactTone(style: BlipStyle): ContactTone = if (style == BlipStyle.DASHED) ContactTone.DIM else ContactTone.FULL

fun blipAlpha(ageMs: Long): Float = (1.0 - ageMs / FULL_FADE_MS).toFloat().coerceIn(MIN_BLIP_ALPHA, 1f)

// A blip past the display edge sits on the rim, so its whole-metre range is the only cue to how far it is.
fun farLabel(rangeM: Double): String? = if (rangeM > DISPLAY_RANGE_M) rangeM.roundToInt().toString() else null

fun nudgeToward(from: PointPx, target: PointPx, px: Float): PointPx {
    val dx = target.x - from.x
    val dy = target.y - from.y
    val distance = hypot(dx, dy)
    if (distance < 1e-3f) return from
    return PointPx(from.x + dx / distance * px, from.y + dy / distance * px)
}

private fun sweepDeg(sector: CoverageSector): Double {
    val raw = sector.toDeg - sector.fromDeg
    return if (raw < 0) raw + 360.0 else raw
}

private fun blipDraw(blip: Blip, origin: PointPx, radius: Float): BlipDraw = BlipDraw(
    center = polarToPx(origin, radius, blip.bearingDeg, blip.rangeM),
    style = blipStyle(blip.confidence),
    alpha = blipAlpha(blip.ageMs),
    id = blip.displayId,
    farLabel = farLabel(blip.rangeM),
)

private fun edgeMarker(blip: Blip, origin: PointPx, radius: Float): EdgeMarkerDraw = EdgeMarkerDraw(
    inner = polarToPx(origin, radius, blip.bearingDeg, EDGE_MARKER_INNER_M),
    outer = polarToPx(origin, radius, blip.bearingDeg, DISPLAY_RANGE_M),
    alpha = blipAlpha(blip.ageMs),
)
