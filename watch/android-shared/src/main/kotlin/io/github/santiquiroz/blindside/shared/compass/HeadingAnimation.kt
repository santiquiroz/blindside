package io.github.santiquiroz.blindside.shared.compass

private const val NANOS_PER_MS = 1_000_000L

data class HeadingAnimation(val currentDeg: Double, val lastFrameNanos: Long)

// Called once per Compose frame: the ring eases toward the latest sensor heading with the existing circular low-pass,
// so it moves at the display's frame rate instead of jumping once per sensor sample.
fun advanceHeading(animation: HeadingAnimation?, targetDeg: Double, frameNanos: Long, tauMs: Double = HEADING_TAU_MS): HeadingAnimation {
    if (animation == null) return HeadingAnimation(normalizedDeg(targetDeg), frameNanos)
    val dtMs = (frameNanos - animation.lastFrameNanos).coerceAtLeast(0L) / NANOS_PER_MS
    return HeadingAnimation(smoothedHeadingDeg(animation.currentDeg, targetDeg, dtMs, tauMs), frameNanos)
}
