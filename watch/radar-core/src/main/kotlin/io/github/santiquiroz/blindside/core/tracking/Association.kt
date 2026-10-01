package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Detection
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.bodyToTracking
import io.github.santiquiroz.blindside.core.geometry.coveringRadars

data class Measurement(val detection: Detection, val position: Point2, val r: Matrix)

fun toMeasurement(detection: Detection, yawDeg: Double, mounts: List<RadarMount>, tuning: TuningParams): Measurement {
    val mount = mounts.first { it.radarId == detection.radarId }
    val overlap = coveringRadars(detection.bodyPoint, mounts, tuning.decode).size >= 2
    val bearingInFrame = mount.yawDeg + detection.radarBearingDeg + yawDeg
    val r = CvKalman.measurementNoise(detection.radarRangeM, detection.radarBearingDeg, bearingInFrame, overlap, tuning.tracking)
    return Measurement(detection, bodyToTracking(detection.bodyPoint, yawDeg), r)
}

fun gateCost(track: Track, measurement: Measurement, params: TrackingParams): Double? {
    val cap = if (track.isLost) params.coastGateSigmaM * params.coastGateSigmaM else null
    val innovation = CvKalman.innovation(track.kalman, measurement.position, measurement.r, cap)
    val limit = if (track.status == TrackStatus.TENTATIVE) params.gateTentativeM else params.gateConfirmedM
    val d2 = innovation.mahalanobis2
    return if (d2 <= params.gateChi2 && innovation.residual.norm <= limit) d2 else null
}

fun associate(tracks: List<Track>, measurements: List<Measurement>, params: TrackingParams): List<Int?> =
    assignExact(measurements.size, tracks.size, params.gateChi2) { d, t -> gateCost(tracks[t], measurements[d], params) }
        .trackIndexByDetection

fun normalizedInnovation(track: Track, measurement: Measurement): Double =
    CvKalman.innovation(track.kalman, measurement.position, measurement.r).mahalanobis2
