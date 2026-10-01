package io.github.santiquiroz.blindside.shared.radar

import androidx.compose.ui.graphics.Color

data class RadarColors(
    val contact: Color,
    val contactDim: Color,
    val fan: Color,
    val ring: Color,
    val dimmed: Color,
)

val CLASSIC_RADAR_COLORS = RadarColors(
    contact = Color(0xFFE53935),
    contactDim = Color(0xFFE53935),
    fan = Color(0xFF3A3A3A),
    ring = Color(0xFF2C2C2C),
    dimmed = Color(0xFF1F1F1F),
)
