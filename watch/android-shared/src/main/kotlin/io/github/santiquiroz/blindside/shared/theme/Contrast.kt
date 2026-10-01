package io.github.santiquiroz.blindside.shared.theme

import kotlin.math.pow

private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
private const val FLARE = 0.05

fun relativeLuminance(argb: Long): Double =
    RED_WEIGHT * linear(channel(argb, 16)) + GREEN_WEIGHT * linear(channel(argb, 8)) + BLUE_WEIGHT * linear(channel(argb, 0))

fun contrastRatio(foreground: Long, background: Long): Double {
    val first = relativeLuminance(foreground)
    val second = relativeLuminance(background)
    return (maxOf(first, second) + FLARE) / (minOf(first, second) + FLARE)
}

private fun channel(argb: Long, shift: Int): Double = ((argb shr shift) and 0xFF) / 255.0

// WCAG 2.x sRGB linearisation.
private fun linear(value: Double): Double = if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
