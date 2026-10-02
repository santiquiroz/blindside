package io.github.santiquiroz.blindside.shared.hud

import java.util.Locale

const val DEFAULT_GAME_DURATION_MS = 18_000_000L
const val FIVE_MIN_MS = 300_000L

val GAME_DURATION_OPTIONS_MS = listOf(3_600_000L, 7_200_000L, 10_800_000L, 18_000_000L, 0L)

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

// The game clock starts with "Iniciar radar"; before the duration elapses it shows what is left, clamped at zero.
fun gameRemainingMs(startElapsedMs: Long, nowElapsedMs: Long, durationMs: Long): Long =
    (durationMs - (nowElapsedMs - startElapsedMs)).coerceIn(0L, durationMs)

fun gameClockText(remainingMs: Long): String {
    val totalSeconds = remainingMs / MS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

// True only on the tick that carries remaining from above the threshold to at-or-below it, so a pulse fires once.
fun crossedThreshold(prevRemainingMs: Long, nowRemainingMs: Long, thresholdMs: Long): Boolean =
    prevRemainingMs > thresholdMs && nowRemainingMs <= thresholdMs

fun nextGameDuration(durationMs: Long): Long {
    val index = GAME_DURATION_OPTIONS_MS.indexOf(durationMs)
    if (index < 0) return GAME_DURATION_OPTIONS_MS.first()
    return GAME_DURATION_OPTIONS_MS[(index + 1) % GAME_DURATION_OPTIONS_MS.size]
}
