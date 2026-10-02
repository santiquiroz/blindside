package io.github.santiquiroz.blindside.shared.hud

import io.github.santiquiroz.blindside.shared.settings.ScreenMode

enum class AlertKind(val critical: Boolean) {
    BELT_LINK_DOWN(true),
    BATTERY_LOW_WATCH(false),
    BATTERY_LOW_PHONE(false),
    BATTERY_LOW_BELT(false),
    HYDRATION(false),
    DUSK_SOON(false),
}

const val ALERT_SHOW_MS = 4_000L
const val HYDRATION_PERIOD_MS = 2_700_000L

data class AlertQueueState(
    val showing: AlertKind? = null,
    val shownSinceMs: Long? = null,
    val pending: List<AlertKind> = emptyList(),
)

// Each kind lines up at most once; a repeat while it is still showing or waiting is dropped.
fun enqueueAlert(state: AlertQueueState, kind: AlertKind): AlertQueueState {
    if (state.showing == kind || kind in state.pending) return state
    return state.copy(pending = state.pending + kind)
}

// One alert at a time for its window; when it expires the next in line takes the rear slot.
fun stepAlertQueue(state: AlertQueueState, nowMs: Long, showMs: Long = ALERT_SHOW_MS): AlertQueueState {
    val expired = state.showing != null && state.shownSinceMs != null && nowMs - state.shownSinceMs >= showMs
    if (state.showing != null && !expired) return state
    val next = state.pending.firstOrNull() ?: return AlertQueueState()
    return AlertQueueState(showing = next, shownSinceMs = nowMs, pending = state.pending.drop(1))
}

fun hydrationDue(lastHydrationMs: Long?, nowMs: Long, periodMs: Long = HYDRATION_PERIOD_MS): Boolean {
    if (lastHydrationMs == null) return true
    return nowMs - lastHydrationMs >= periodMs
}

// Spec §8.3: in Sigilo only critical alerts vibrate; in Vista any alert may.
fun alertVibrates(kind: AlertKind, mode: ScreenMode): Boolean = kind.critical || mode == ScreenMode.VISTA
