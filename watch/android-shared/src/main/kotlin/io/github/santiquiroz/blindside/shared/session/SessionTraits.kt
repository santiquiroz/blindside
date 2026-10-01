package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.haptics.HapticSink

data class SessionTraits(
    val link: BeltLinkProfile,
    val records: Boolean = true,
    val readsDeviceSensors: Boolean = true,
    val vibrates: Boolean = true,
    val framePeriodMs: Long? = null,
    val recordingTag: String? = null,
)

val SILENT_HAPTICS = HapticSink { }

fun hapticsFor(traits: SessionTraits, player: HapticSink): HapticSink = if (traits.vibrates) player else SILENT_HAPTICS

fun recordingTagFor(traits: SessionTraits, source: SessionSource): String = traits.recordingTag ?: source.name
