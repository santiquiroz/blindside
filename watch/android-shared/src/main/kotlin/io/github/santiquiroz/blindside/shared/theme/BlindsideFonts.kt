package io.github.santiquiroz.blindside.shared.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.santiquiroz.blindside.shared.R

object BlindsideFonts {
    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_nl_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_nl_bold, FontWeight.Bold),
    )
    val Sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    )

    // Distances, bearings and counters must not change width as their digits change.
    val Numbers = TextStyle(fontFamily = Mono, fontFeatureSettings = "tnum")
}
