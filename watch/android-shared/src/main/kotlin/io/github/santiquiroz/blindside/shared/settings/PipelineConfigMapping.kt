package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.DopplerParams
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.config.defaultMounts

fun toPipelineConfig(settings: AppSettings): PipelineConfig = PipelineConfig(
    tuning = TuningParams(doppler = DopplerParams(enabled = settings.dopplerFilter)),
    mounts = mountsFor(settings),
)

fun mountsFor(settings: AppSettings): List<RadarMount> =
    defaultMounts(settings.handedness).map { applyRadarSettings(it, settings.radar(it.radarId)) }

fun applyRadarSettings(mount: RadarMount, radar: RadarSettings): RadarMount =
    mount.copy(yawDeg = radar.yawDegOverride ?: mount.yawDeg, flipX = radar.flipX, speedSign = radar.speedSign)

fun effectiveYawDeg(settings: AppSettings, radarId: Int): Double =
    mountsFor(settings).firstOrNull { it.radarId == radarId }?.yawDeg ?: 0.0

fun AppSettings.withYawNudged(radarId: Int, deltaDeg: Double): AppSettings =
    withRadar(radar(radarId).copy(yawDegOverride = stepYaw(effectiveYawDeg(this, radarId), deltaDeg)))
