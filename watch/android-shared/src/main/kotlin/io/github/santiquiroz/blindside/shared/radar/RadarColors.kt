package io.github.santiquiroz.blindside.shared.radar

import androidx.compose.ui.graphics.Color
import io.github.santiquiroz.blindside.shared.settings.ContactColor
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors

data class RadarColors(
    val contact: Color,
    val contactDim: Color,
    val fan: Color,
    val ring: Color,
    val dimmed: Color,
)

val TACTICAL_RADAR_COLORS = RadarColors(
    contact = BlindsideColors.Accent,
    contactDim = BlindsideColors.AccentDim,
    fan = BlindsideColors.Ring,
    ring = BlindsideColors.Ring,
    dimmed = BlindsideColors.Surface2,
)

// v1 spec §5.3: red against the rivals' view, green against night-vision tubes. Only the contacts change.
val RED_RADAR_COLORS = TACTICAL_RADAR_COLORS.copy(contact = BlindsideColors.AlertRed, contactDim = BlindsideColors.AlertRedDim)

fun radarColorsFor(color: ContactColor): RadarColors = when (color) {
    ContactColor.GREEN -> TACTICAL_RADAR_COLORS
    ContactColor.RED -> RED_RADAR_COLORS
}
