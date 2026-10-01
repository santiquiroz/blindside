package io.github.santiquiroz.blindside.shared.compass

import androidx.compose.ui.graphics.Color
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors

data class CompassColors(val tick: Color, val major: Color, val north: Color, val letter: Color, val index: Color)

const val CALIBRATE_ALPHA = 0.4f

fun compassColors(screenMode: ScreenMode, trust: CompassTrust): CompassColors {
    val alpha = if (trust == CompassTrust.GOOD) 1f else CALIBRATE_ALPHA
    val north = if (screenMode == ScreenMode.VISTA) BlindsideColors.Accent else BlindsideColors.AccentDim
    return CompassColors(
        tick = BlindsideColors.Ring.copy(alpha = alpha),
        major = BlindsideColors.AccentDim.copy(alpha = alpha),
        north = north.copy(alpha = alpha),
        letter = BlindsideColors.Text2.copy(alpha = alpha),
        index = BlindsideColors.Accent,
    )
}
