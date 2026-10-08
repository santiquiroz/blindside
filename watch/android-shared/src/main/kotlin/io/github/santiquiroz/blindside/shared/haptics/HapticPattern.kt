package io.github.santiquiroz.blindside.shared.haptics

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.scene.Side

data class HapticPattern(val timingsMs: List<Long>)

const val SHORT_PULSE_MS = 100L
const val LONG_PULSE_MS = 350L
const val PULSE_GAP_MS = 150L
const val SYSTEM_BUZZ_MS = 1_200L
const val ALLY_PULSE_MS = 40L
const val FULL_AMPLITUDE = 255

val CENTER_PATTERN = HapticPattern(listOf(0L, LONG_PULSE_MS))
val LEFT_PATTERN = HapticPattern(listOf(0L, SHORT_PULSE_MS, PULSE_GAP_MS, SHORT_PULSE_MS))
val RIGHT_PATTERN = HapticPattern(listOf(0L, SHORT_PULSE_MS, PULSE_GAP_MS, LONG_PULSE_MS))
val SYSTEM_PATTERN = HapticPattern(listOf(0L, SYSTEM_BUZZ_MS))
val ALLY_PATTERN = HapticPattern(listOf(0L, ALLY_PULSE_MS))

fun patternFor(side: Side): HapticPattern = when (side) {
    Side.LEFT -> LEFT_PATTERN
    Side.CENTER -> CENTER_PATTERN
    Side.RIGHT -> RIGHT_PATTERN
}

fun hapticFor(event: PipelineEvent): HapticPattern? = when (event) {
    is ContactAlert -> patternFor(event.side)
    is SystemAlert -> SYSTEM_PATTERN
    else -> null
}

fun pulsesMs(pattern: HapticPattern): List<Long> =
    pattern.timingsMs.filterIndexed { index, _ -> isPulseIndex(index) }

fun amplitudesFor(pattern: HapticPattern): List<Int> =
    pattern.timingsMs.indices.map { index -> if (isPulseIndex(index)) FULL_AMPLITUDE else 0 }

private fun isPulseIndex(index: Int): Boolean = index % 2 == 1
