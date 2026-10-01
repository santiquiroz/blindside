package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.shared.settings.VibrationUsage

enum class HapticRenderer { AMPLITUDE_WAVEFORM, ON_OFF_WAVEFORM }

enum class InterruptionFilter { ALL, PRIORITY, ALARMS, NONE, UNKNOWN }

fun interface HapticSink {
    fun play(pattern: HapticPattern)
}

fun chooseRenderer(hasAmplitudeControl: Boolean): HapticRenderer =
    if (hasAmplitudeControl) HapticRenderer.AMPLITUDE_WAVEFORM else HapticRenderer.ON_OFF_WAVEFORM

fun interruptionFilterFrom(code: Int): InterruptionFilter = when (code) {
    1 -> InterruptionFilter.ALL
    2 -> InterruptionFilter.PRIORITY
    3 -> InterruptionFilter.NONE
    4 -> InterruptionFilter.ALARMS
    else -> InterruptionFilter.UNKNOWN
}

fun dndMaySilence(filter: InterruptionFilter, usage: VibrationUsage): Boolean = when (filter) {
    InterruptionFilter.ALL -> false
    InterruptionFilter.PRIORITY, InterruptionFilter.ALARMS -> usage == VibrationUsage.NOTIFICATION
    InterruptionFilter.NONE, InterruptionFilter.UNKNOWN -> true
}
