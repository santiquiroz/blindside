package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.DopplerParams
import io.github.santiquiroz.blindside.core.geometry.Detection
import io.github.santiquiroz.blindside.core.geometry.Point2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class EgoSample(val tMs: Long, val bearingDeg: Double, val radialMps: Double)

data class EgoMotion(val samples: List<EgoSample> = emptyList()) {
    fun with(detections: List<Detection>, tMs: Long, params: DopplerParams): EgoMotion {
        val kept = samples.filter { it.tMs >= tMs - params.windowMs }
        val added = detections.filter { it.radarRangeM >= params.minRangeM }
            .map { EgoSample(it.tMs, it.bodyPoint.bearingDeg, it.radialSpeedMps) }
        return EgoMotion(kept + added)
    }

    fun velocity(params: DopplerParams): Point2? {
        if (samples.size < params.minDetections) return null
        val first = leastSquares(samples) ?: return null
        val inliers = samples.filter { abs(it.radialMps - expectedStaticRadialMps(first, it.bearingDeg)) <= params.outlierMps }
        if (inliers.size < params.minDetections) return null
        if (bearingClusters(inliers, params.clusterGapDeg) < params.minBearingClusters) return null
        val refit = leastSquares(inliers) ?: return null
        if (rmsResidualMps(refit, inliers) > params.maxRmsMps) return null
        if (refit.norm < params.minSpeedMps || refit.norm > params.maxSpeedMps) return null
        return refit
    }
}

fun expectedStaticRadialMps(velocity: Point2, bearingDeg: Double): Double {
    val theta = Math.toRadians(bearingDeg)
    return -(velocity.x * sin(theta) + velocity.y * cos(theta))
}

fun isStaticEcho(detection: Detection, velocity: Point2, params: DopplerParams): Boolean {
    val expected = expectedStaticRadialMps(velocity, detection.bodyPoint.bearingDeg)
    return abs(detection.radialSpeedMps - expected) <= params.staticToleranceMps
}

private fun bearingClusters(samples: List<EgoSample>, gapDeg: Double): Int {
    val sorted = samples.map { it.bearingDeg }.sorted()
    var clusters = 0
    var prev: Double? = null
    for (bearing in sorted) {
        if (prev == null || bearing - prev > gapDeg) clusters++
        prev = bearing
    }
    return clusters
}

private fun leastSquares(samples: List<EgoSample>): Point2? {
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    var sxr = 0.0
    var syr = 0.0
    for (sample in samples) {
        val theta = Math.toRadians(sample.bearingDeg)
        val ax = -sin(theta)
        val ay = -cos(theta)
        sxx += ax * ax
        sxy += ax * ay
        syy += ay * ay
        sxr += ax * sample.radialMps
        syr += ay * sample.radialMps
    }
    val det = sxx * syy - sxy * sxy
    if (abs(det) <= SINGULAR_DET) return null
    return Point2((syy * sxr - sxy * syr) / det, (sxx * syr - sxy * sxr) / det)
}

private fun rmsResidualMps(velocity: Point2, samples: List<EgoSample>): Double {
    val meanSquare = samples.sumOf {
        val residual = it.radialMps - expectedStaticRadialMps(velocity, it.bearingDeg)
        residual * residual
    } / samples.size
    return sqrt(meanSquare)
}

private const val SINGULAR_DET = 1e-9
