package io.github.santiquiroz.blindside.shared.hud

enum class BezelField { HEADING, CLOCK, GAME_TIME }

const val BEZEL_ROTATE_MS = 4_000L

// The bezel window (where the heading lives) cycles its content every few seconds; a short tap pins what is showing.
fun bezelFieldAt(elapsedMs: Long, pinned: BezelField?, rotatePeriodMs: Long = BEZEL_ROTATE_MS): BezelField {
    pinned?.let { return it }
    val index = ((elapsedMs.coerceAtLeast(0L) / rotatePeriodMs) % BezelField.entries.size).toInt()
    return BezelField.entries[index]
}

fun toggledPin(pinned: BezelField?, showing: BezelField): BezelField? = if (pinned == null) showing else null
