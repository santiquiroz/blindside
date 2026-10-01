package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.Handedness

data class SharedSettings(
    val handedness: Handedness,
    val radars: List<RadarSettings>,
    val posture: WatchPosture,
    val updatedMs: Long,
)

fun sharedSettingsOf(settings: AppSettings): SharedSettings =
    SharedSettings(settings.handedness, settings.radars, settings.posture, settings.sharedUpdatedMs)

fun AppSettings.withShared(shared: SharedSettings): AppSettings =
    copy(handedness = shared.handedness, radars = shared.radars, posture = shared.posture, sharedUpdatedMs = shared.updatedMs)

// Strictly newer only: equal stamps are the echo of our own write coming back from the other device.
fun shouldAdopt(local: AppSettings, remote: SharedSettings): Boolean = remote.updatedMs > local.sharedUpdatedMs

fun AppSettings.adoptingNewer(remote: SharedSettings): AppSettings = if (shouldAdopt(this, remote)) withShared(remote) else this

// A transform that brings its own stamp (an adoption) keeps it; a local edit of a shared field is stamped now.
fun stampSharedEdit(before: AppSettings, after: AppSettings, nowMs: Long): AppSettings = when {
    after.sharedUpdatedMs != before.sharedUpdatedMs -> after
    sharedSettingsOf(after) != sharedSettingsOf(before) -> after.copy(sharedUpdatedMs = nextStamp(before.sharedUpdatedMs, nowMs))
    else -> after
}

fun isStamped(shared: SharedSettings): Boolean = shared.updatedMs > 0L

// An adopted stamp may come from a clock running ahead; our own later edit must still be the newest write on both devices.
private fun nextStamp(previousMs: Long, nowMs: Long): Long = maxOf(nowMs, previousMs + 1)
