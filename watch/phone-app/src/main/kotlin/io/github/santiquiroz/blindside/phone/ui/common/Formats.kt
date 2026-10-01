package io.github.santiquiroz.blindside.phone.ui.common

import java.util.Locale
import kotlin.math.roundToInt

private val SPANISH: Locale = Locale.forLanguageTag("es")
private const val KIB = 1_024L
private const val MIB = 1_024L * 1_024L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

fun formatDecimal(value: Double): String = String.format(SPANISH, "%.1f", value)

fun formatMeters(meters: Double): String = "${formatDecimal(meters)} m"

fun formatSeconds(ms: Long): String = "${formatDecimal(ms / 1_000.0)} s"

fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt()} %"

fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1_000L
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

fun formatUptime(seconds: Long): String = when {
    seconds < SECONDS_PER_MINUTE -> "$seconds s"
    seconds < SECONDS_PER_HOUR -> String.format(Locale.ROOT, "%d min %02d s", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    else -> String.format(Locale.ROOT, "%d h %02d min", seconds / SECONDS_PER_HOUR, (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
}

fun formatBytes(bytes: Long): String = when {
    bytes < KIB -> "$bytes B"
    bytes < MIB -> "${formatDecimal(bytes.toDouble() / KIB)} KB"
    else -> "${formatDecimal(bytes.toDouble() / MIB)} MB"
}

fun formatAgo(ms: Long): String = "hace ${formatUptime(ms.coerceAtLeast(0L) / 1_000L)}"
