package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.ui.common.formatMeters
import io.github.santiquiroz.blindside.phone.ui.common.formatSeconds
import io.github.santiquiroz.blindside.shared.radar.BlipStyle
import io.github.santiquiroz.blindside.shared.radar.blipStyle
import java.util.Locale
import kotlin.math.roundToInt

data class ContactRow(
    val id: String,
    val distance: String,
    val bearing: String,
    val confidence: String,
    val age: String,
    val style: BlipStyle,
)

fun contactRows(scene: RadarScene?): List<ContactRow> =
    scene?.takeIf { it.linkUp && !it.eliminated }?.blips.orEmpty().sortedBy { it.rangeM }.map(::contactRow)

fun contactRow(blip: Blip): ContactRow = ContactRow(
    id = "#${blip.displayId}",
    distance = formatMeters(blip.rangeM),
    bearing = formatBearing(blip.bearingDeg),
    confidence = confidenceLabel(blip.confidence, blip.outOfView),
    age = formatSeconds(blip.ageMs),
    style = blipStyle(blip.confidence),
)

fun formatBearing(deg: Double): String {
    val rounded = deg.roundToInt()
    return if (rounded == 0) "0°" else String.format(Locale.ROOT, "%+d°", rounded)
}

fun confidenceLabel(confidence: Confidence, outOfView: Boolean): String = when {
    outOfView -> "Fuera de vista"
    confidence == Confidence.BOTH -> "Ambos radares"
    confidence == Confidence.SINGLE -> "Un radar"
    else -> "Perdido"
}
