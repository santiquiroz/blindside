package io.github.santiquiroz.blindside.phone.ui.theme

const val MOTION_MIN_MS = 150
const val MOTION_MAX_MS = 300

// Spec §6: 150-300 ms, and "reducir movimiento" (animator scale 0) turns animation off.
fun motionDurationMs(baseMs: Int, animatorScale: Float): Int =
    if (animatorScale <= 0f) 0 else baseMs.coerceIn(MOTION_MIN_MS, MOTION_MAX_MS)
