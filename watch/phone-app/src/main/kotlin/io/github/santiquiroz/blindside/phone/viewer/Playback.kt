package io.github.santiquiroz.blindside.phone.viewer

val PLAYBACK_SPEEDS: List<Int> = listOf(1, 2, 4, 8)

data class Playback(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Int = 1,
    val playing: Boolean = false,
)

fun advanced(playback: Playback, wallDeltaMs: Long): Playback {
    if (!playback.playing) return playback
    val next = (playback.positionMs + wallDeltaMs.coerceAtLeast(0L) * playback.speed).coerceAtMost(playback.durationMs)
    return playback.copy(positionMs = next, playing = next < playback.durationMs)
}

// Play at the end starts over, like any media player.
fun toggledPlay(playback: Playback): Playback = when {
    playback.playing -> playback.copy(playing = false)
    playback.positionMs >= playback.durationMs -> playback.copy(positionMs = 0L, playing = true)
    else -> playback.copy(playing = true)
}

fun withSpeed(playback: Playback, speed: Int): Playback = if (speed in PLAYBACK_SPEEDS) playback.copy(speed = speed) else playback

fun seekedTo(playback: Playback, positionMs: Long): Playback = playback.copy(positionMs = positionMs.coerceIn(0L, playback.durationMs))
