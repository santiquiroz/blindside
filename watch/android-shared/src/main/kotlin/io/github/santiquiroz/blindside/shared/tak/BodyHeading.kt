package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.shared.compass.normalizedDeg

const val HEADING_FRESH_NANOS = 3_000_000_000L
const val HEADING_BELT_HOLD_NANOS = 120_000_000_000L

data class HeadingAnchor(val offsetDeg: Double, val atNanos: Long)

fun anchorOf(frontHeadingDeg: Double, bodyYawDeg: Double, atNanos: Long): HeadingAnchor =
    HeadingAnchor(normalizedDeg(frontHeadingDeg - bodyYawDeg), atNanos)

fun bodyHeadingDeg(anchor: HeadingAnchor?, bodyYawDeg: Double, yawFromBelt: Boolean, nowNanos: Long): Double? {
    if (anchor == null) return null
    if (nowNanos - anchor.atNanos <= HEADING_FRESH_NANOS) return headed(anchor, bodyYawDeg)
    if (yawFromBelt && nowNanos - anchor.atNanos <= HEADING_BELT_HOLD_NANOS) return headed(anchor, bodyYawDeg)
    return null
}

private fun headed(anchor: HeadingAnchor, bodyYawDeg: Double): Double =
    normalizedDeg(anchor.offsetDeg + bodyYawDeg)
