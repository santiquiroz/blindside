package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams

enum class TrackStatus { TENTATIVE, CONFIRMED, COASTING, OUT_OF_VIEW }

data class WindowMark(val startMs: Long, val hit: Boolean)

data class Track(
    val id: Int,
    val displayId: Int,
    val kalman: KalmanState,
    val stateMs: Long,
    val bornMs: Long,
    val lastHitMs: Long,
    val status: TrackStatus = TrackStatus.TENTATIVE,
    val outcomes: List<WindowMark> = emptyList(),
    val windowRadars: Set<Int> = emptySet(),
    val previousWindowRadars: Set<Int> = emptySet(),
    val lostStill: Boolean = false,
) {
    val hitThisWindow: Boolean get() = windowRadars.isNotEmpty()
    val isLost: Boolean get() = status == TrackStatus.COASTING || status == TrackStatus.OUT_OF_VIEW
    val isDisplayed: Boolean get() = status != TrackStatus.TENTATIVE
    val recentRadars: Set<Int> get() = windowRadars + previousWindowRadars

    fun predictedTo(tMs: Long, params: TrackingParams): Track {
        val dtS = (tMs - stateMs) / 1000.0
        if (dtS <= 0.0) return this
        val next = if (isLost) {
            CvKalman.coast(kalman, dtS, params.processNoise, params.coastSpeedTauS)
        } else {
            CvKalman.predict(kalman, dtS, params.processNoise)
        }
        return copy(kalman = next, stateMs = tMs)
    }
}
