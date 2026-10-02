package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import io.github.santiquiroz.blindside.shared.compass.shortestTurnDeg

// How far between the last belt frame and the next the display is, 0..1, so contacts glide at frame rate.
fun frameFraction(sinceFrameMs: Long, framePeriodMs: Long): Float {
    if (framePeriodMs <= 0L) return 1f
    return (sinceFrameMs.toFloat() / framePeriodMs).coerceIn(0f, 1f)
}

// The next frame is the truth: its set of ids is drawn exactly. A contact also present last frame starts from there,
// so it slides instead of teleporting; a newborn or a vanished one is never invented or kept.
fun interpolatedBlips(previous: List<Blip>, next: List<Blip>, t: Float): List<Blip> =
    next.map { target -> previous.firstOrNull { it.displayId == target.displayId }?.let { easedBlip(it, target, t) } ?: target }

private fun easedBlip(from: Blip, to: Blip, t: Float): Blip = to.copy(
    bearingDeg = normalizedDeg(from.bearingDeg + shortestTurnDeg(from.bearingDeg, to.bearingDeg) * t),
    rangeM = from.rangeM + (to.rangeM - from.rangeM) * t,
)
