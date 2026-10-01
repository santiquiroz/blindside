package io.github.santiquiroz.blindside.phone.ui.theme

import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

// Short names for the shared §6 tokens (plan 05 Task 14); no colour value lives in the phone.
val BgColor: Color = BlindsideColors.Bg
val SurfaceColor: Color = BlindsideColors.Surface
val Surface2Color: Color = BlindsideColors.Surface2
val RingColor: Color = BlindsideColors.Ring
val AccentColor: Color = BlindsideColors.Accent
val AccentDimColor: Color = BlindsideColors.AccentDim
val AlertRedColor: Color = BlindsideColors.AlertRed
val AlertRedDimColor: Color = BlindsideColors.AlertRedDim
val WarnColor: Color = BlindsideColors.Warn
val TextColor: Color = BlindsideColors.Text
val Text2Color: Color = BlindsideColors.Text2

val NumberStyle: TextStyle = BlindsideFonts.Numbers

private val PhoneColors: ColorScheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = BgColor,
    primaryContainer = AccentDimColor,
    onPrimaryContainer = TextColor,
    secondary = AccentDimColor,
    onSecondary = TextColor,
    secondaryContainer = Surface2Color,
    onSecondaryContainer = TextColor,
    tertiary = WarnColor,
    onTertiary = BgColor,
    background = BgColor,
    onBackground = TextColor,
    surface = BgColor,
    onSurface = TextColor,
    surfaceVariant = Surface2Color,
    onSurfaceVariant = Text2Color,
    surfaceContainerLowest = BgColor,
    surfaceContainerLow = SurfaceColor,
    surfaceContainer = SurfaceColor,
    surfaceContainerHigh = Surface2Color,
    surfaceContainerHighest = Surface2Color,
    error = AlertRedColor,
    onError = BgColor,
    errorContainer = AlertRedDimColor,
    onErrorContainer = TextColor,
    outline = RingColor,
    outlineVariant = RingColor,
)

private val PhoneTypography: Typography = Typography().withFamily(BlindsideFonts.Sans)

@Composable
fun BlindsideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhoneColors, typography = PhoneTypography, content = content)
}

@Composable
fun rememberMotionDurationMs(baseMs: Int): Int {
    val context = LocalContext.current
    return remember(baseMs) {
        motionDurationMs(baseMs, Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}

private fun Typography.withFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
