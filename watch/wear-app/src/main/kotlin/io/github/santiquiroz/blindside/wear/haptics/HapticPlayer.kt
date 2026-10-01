package io.github.santiquiroz.blindside.wear.haptics

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.santiquiroz.blindside.shared.settings.VibrationUsage

class HapticPlayer(private val vibrator: Vibrator, usage: VibrationUsage) : HapticSink {
    private val renderer = chooseRenderer(vibrator.hasAmplitudeControl())
    private val attributes = VibrationAttributes.createForUsage(androidUsage(usage))

    override fun play(pattern: HapticPattern) {
        vibrator.vibrate(effectFor(pattern), attributes)
    }

    fun hasAmplitudeControl(): Boolean = renderer == HapticRenderer.AMPLITUDE_WAVEFORM

    fun supportsPrimitives(): Boolean = vibrator.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
    )

    private fun effectFor(pattern: HapticPattern): VibrationEffect = when (renderer) {
        HapticRenderer.AMPLITUDE_WAVEFORM -> VibrationEffect.createWaveform(
            pattern.timingsMs.toLongArray(),
            amplitudesFor(pattern).toIntArray(),
            NO_REPEAT,
        )
        HapticRenderer.ON_OFF_WAVEFORM -> VibrationEffect.createWaveform(pattern.timingsMs.toLongArray(), NO_REPEAT)
    }

    companion object {
        private const val NO_REPEAT = -1

        fun create(context: Context, usage: VibrationUsage): HapticPlayer =
            HapticPlayer(context.getSystemService(VibratorManager::class.java).defaultVibrator, usage)
    }
}

private fun androidUsage(usage: VibrationUsage): Int = when (usage) {
    VibrationUsage.ALARM -> VibrationAttributes.USAGE_ALARM
    VibrationUsage.NOTIFICATION -> VibrationAttributes.USAGE_NOTIFICATION
}
