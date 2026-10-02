package io.github.santiquiroz.blindside.shared.radar

import kotlin.math.exp

const val SCENE_SPIN_WASHOUT_TAU_MS = 500.0
const val MAX_SCENE_SPIN_DEG = 45.0

// The belt corrects body yaw at 10 Hz; between its frames the watch gyro turns the drawing at once, then this
// washout decays the extra spin toward zero so the belt's own compensation takes back over without a fight.
fun advanceSceneSpinDeg(
    currentDeg: Double,
    yawRateRadPerSec: Double,
    dtMs: Long,
    washoutTauMs: Double = SCENE_SPIN_WASHOUT_TAU_MS,
): Double {
    val dt = dtMs.coerceAtLeast(0L).toDouble()
    val added = Math.toDegrees(yawRateRadPerSec * dt / 1_000.0)
    val decayed = (currentDeg + added) * exp(-dt / washoutTauMs)
    return decayed.coerceIn(-MAX_SCENE_SPIN_DEG, MAX_SCENE_SPIN_DEG)
}
