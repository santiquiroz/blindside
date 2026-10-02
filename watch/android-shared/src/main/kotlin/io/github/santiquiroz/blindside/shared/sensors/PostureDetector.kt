package io.github.santiquiroz.blindside.shared.sensors

import io.github.santiquiroz.blindside.shared.settings.WatchPosture

const val POSTURE_ENTER_DEG = 25.0
const val POSTURE_EXIT_DEG = 35.0
const val POSTURE_DWELL_MS = 400L

data class PostureDetectorState(val tactical: Boolean = false, val enteringSinceMs: Long? = null)

fun stepPostureDetector(
    state: PostureDetectorState,
    angleDeg: Double,
    nowMs: Long,
    enterDeg: Double = POSTURE_ENTER_DEG,
    exitDeg: Double = POSTURE_EXIT_DEG,
    dwellMs: Long = POSTURE_DWELL_MS,
): PostureDetectorState {
    if (state.tactical) return if (angleDeg > exitDeg) PostureDetectorState(false, null) else state
    if (angleDeg >= enterDeg) return PostureDetectorState(false, null)
    val since = state.enteringSinceMs ?: nowMs
    if (nowMs - since >= dwellMs) return PostureDetectorState(true, null)
    return state.copy(enteringSinceMs = since)
}

// AUTO follows the detector and the calibrated side; the three fixed choices turn the drawing by their own angle.
fun effectivePostureRotationDeg(posture: WatchPosture, tactical: Boolean, template: GravityTemplate?): Float = when (posture) {
    WatchPosture.AUTO -> if (tactical && template != null) template.rotationDeg else 0f
    else -> posture.rotationDeg
}
