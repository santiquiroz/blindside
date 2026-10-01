package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.settings.ScreenMode

const val VISTA_FRAME_MS = 33L
const val SIGILO_FRAME_MS = 100L
const val IDLE_TICK_POLL_MS = 500L

// Spec §5.4: ambient hides contacts and refreshes about once a minute, so ticking would only burn battery.
fun scenePeriodMs(radarVisible: Boolean, mode: ScreenMode, ambient: Boolean): Long? = when {
    !radarVisible || ambient -> null
    mode == ScreenMode.VISTA -> VISTA_FRAME_MS
    else -> SIGILO_FRAME_MS
}

// A host with its own frame rate (the phone) keeps the same rule: no scenes off screen or in ambient.
fun pacedPeriodMs(framePeriodMs: Long?, radarVisible: Boolean, mode: ScreenMode, ambient: Boolean): Long? {
    val screenPeriod = scenePeriodMs(radarVisible, mode, ambient) ?: return null
    return framePeriodMs ?: screenPeriod
}
