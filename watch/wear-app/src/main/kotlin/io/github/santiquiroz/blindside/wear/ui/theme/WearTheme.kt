package io.github.santiquiroz.blindside.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

private val WearColors = Colors(
    primary = BlindsideColors.Accent,
    primaryVariant = BlindsideColors.AccentDim,
    secondary = BlindsideColors.Accent,
    secondaryVariant = BlindsideColors.AccentDim,
    background = BlindsideColors.Bg,
    surface = BlindsideColors.Surface,
    error = BlindsideColors.AlertRed,
    onPrimary = BlindsideColors.Bg,
    onSecondary = BlindsideColors.Bg,
    onBackground = BlindsideColors.Text,
    onSurface = BlindsideColors.Text,
    onSurfaceVariant = BlindsideColors.Text2,
    onError = BlindsideColors.Bg,
)

private val WearTypography = Typography(defaultFontFamily = BlindsideFonts.Sans)

@Composable
fun BlindsideWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WearColors, typography = WearTypography, content = content)
}
