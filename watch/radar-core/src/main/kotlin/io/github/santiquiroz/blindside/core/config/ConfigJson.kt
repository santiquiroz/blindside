package io.github.santiquiroz.blindside.core.config

import io.github.santiquiroz.blindside.core.protocol.MiniJson

fun PipelineConfig.toJson(): String = MiniJson.obj(
    listOf(
        "tuning" to tuning.toJson(),
        "mounts" to MiniJson.array(mounts.map { writeSection(it, MOUNT_FIELDS) }),
    ),
)

fun TuningParams.toJson(): String = MiniJson.obj(
    listOf(
        "decode" to writeSection(decode, DECODE_FIELDS),
        "imu" to writeSection(imu, IMU_FIELDS),
        "motion" to writeSection(motion, MOTION_FIELDS),
        "tracking" to writeSection(tracking, TRACKING_FIELDS),
        "alerts" to writeSection(alerts, ALERT_FIELDS),
        "clock" to writeSection(clock, CLOCK_FIELDS),
        "status" to writeSection(status, STATUS_FIELDS),
    ),
)

fun pipelineConfigFromJson(json: String): PipelineConfig = pipelineConfigFromValue(MiniJson.parseOrNull(json))

// Missing keys keep their defaults, so recordings made with an older TuningParams still replay.
fun pipelineConfigFromValue(value: Any?): PipelineConfig {
    val root = value as? Map<*, *> ?: return PipelineConfig()
    val mounts = (root["mounts"] as? List<*>).orEmpty().map { readSection(EMPTY_MOUNT, it, MOUNT_FIELDS) }
    return PipelineConfig(tuning = tuningFrom(root["tuning"]), mounts = mounts.ifEmpty { defaultMounts(Handedness.RIGHT) })
}

private fun tuningFrom(value: Any?): TuningParams {
    val root = value as? Map<*, *> ?: return TuningParams()
    val defaults = TuningParams()
    return TuningParams(
        decode = readSection(defaults.decode, root["decode"], DECODE_FIELDS),
        imu = readSection(defaults.imu, root["imu"], IMU_FIELDS),
        motion = readSection(defaults.motion, root["motion"], MOTION_FIELDS),
        tracking = readSection(defaults.tracking, root["tracking"], TRACKING_FIELDS),
        alerts = readSection(defaults.alerts, root["alerts"], ALERT_FIELDS),
        clock = readSection(defaults.clock, root["clock"], CLOCK_FIELDS),
        status = readSection(defaults.status, root["status"], STATUS_FIELDS),
    )
}

private fun <P> writeSection(params: P, fields: List<Field<P>>): String =
    MiniJson.obj(fields.map { it.name to it.write(params) })

private fun <P> readSection(defaults: P, value: Any?, fields: List<Field<P>>): P {
    val map = value as? Map<*, *> ?: return defaults
    return fields.fold(defaults) { params, field -> map[field.name]?.let { field.read(params, it) } ?: params }
}

private val EMPTY_MOUNT = RadarMount(radarId = 0, xM = 0.0, yM = 0.0, yawDeg = 0.0)

private val DECODE_FIELDS: List<Field<DecodeParams>> = fieldsOf {
    listOf(
        double("maxAbsAngleDeg", { it.maxAbsAngleDeg }) { p, v -> p.copy(maxAbsAngleDeg = v) },
        double("maxSpeedMps", { it.maxSpeedMps }) { p, v -> p.copy(maxSpeedMps = v) },
        intSet("validResolutionsMm", { it.validResolutionsMm }) { p, v -> p.copy(validResolutionsMm = v) },
        int("staleRepeatFrames", { it.staleRepeatFrames }) { p, v -> p.copy(staleRepeatFrames = v) },
        double("nearFieldM", { it.nearFieldM }) { p, v -> p.copy(nearFieldM = v) },
        double("coneHalfAngleDeg", { it.coneHalfAngleDeg }) { p, v -> p.copy(coneHalfAngleDeg = v) },
        double("maxRangeM", { it.maxRangeM }) { p, v -> p.copy(maxRangeM = v) },
    )
}

private val IMU_FIELDS: List<Field<ImuParams>> = fieldsOf {
    listOf(
        int("bootWindowSamples", { it.bootWindowSamples }) { p, v -> p.copy(bootWindowSamples = v) },
        int("restWindowSamples", { it.restWindowSamples }) { p, v -> p.copy(restWindowSamples = v) },
        double("stillMaxSpreadDps", { it.stillMaxSpreadDps }) { p, v -> p.copy(stillMaxSpreadDps = v) },
        double("stillMaxAccelStdG", { it.stillMaxAccelStdG }) { p, v -> p.copy(stillMaxAccelStdG = v) },
        double("defectiveMeanDps", { it.defectiveMeanDps }) { p, v -> p.copy(defectiveMeanDps = v) },
        double("reseedMinOffsetDps", { it.reseedMinOffsetDps }) { p, v -> p.copy(reseedMinOffsetDps = v) },
        double("restBiasTauS", { it.restBiasTauS }) { p, v -> p.copy(restBiasTauS = v) },
        double("witnessMaxDps", { it.witnessMaxDps }) { p, v -> p.copy(witnessMaxDps = v) },
        int("witnessMinSamples", { it.witnessMinSamples }) { p, v -> p.copy(witnessMinSamples = v) },
        long("witnessHistoryMs", { it.witnessHistoryMs }) { p, v -> p.copy(witnessHistoryMs = v) },
        double("gravityTauS", { it.gravityTauS }) { p, v -> p.copy(gravityTauS = v) },
        double("gravityRejectG", { it.gravityRejectG }) { p, v -> p.copy(gravityRejectG = v) },
        double("proneAngleDeg", { it.proneAngleDeg }) { p, v -> p.copy(proneAngleDeg = v) },
        double("gyroScale", { it.gyroScale }) { p, v -> p.copy(gyroScale = v) },
        long("radarImuDelayMs", { it.radarImuDelayMs }) { p, v -> p.copy(radarImuDelayMs = v) },
        long("yawHistoryMs", { it.yawHistoryMs }) { p, v -> p.copy(yawHistoryMs = v) },
        long("yawExtrapolationMaxMs", { it.yawExtrapolationMaxMs }) { p, v -> p.copy(yawExtrapolationMaxMs = v) },
        int("yawRateSamplesForExtrapolation", { it.yawRateSamplesForExtrapolation }) { p, v -> p.copy(yawRateSamplesForExtrapolation = v) },
        double("yawBlendTauMs", { it.yawBlendTauMs }) { p, v -> p.copy(yawBlendTauMs = v) },
    )
}

private val MOTION_FIELDS: List<Field<MotionParams>> = fieldsOf {
    listOf(
        double("turningRateDps", { it.turningRateDps }) { p, v -> p.copy(turningRateDps = v) },
        double("turningTauS", { it.turningTauS }) { p, v -> p.copy(turningTauS = v) },
        double("walkingAccelStdG", { it.walkingAccelStdG }) { p, v -> p.copy(walkingAccelStdG = v) },
        long("walkingWindowMs", { it.walkingWindowMs }) { p, v -> p.copy(walkingWindowMs = v) },
        long("stepHoldMs", { it.stepHoldMs }) { p, v -> p.copy(stepHoldMs = v) },
        double("stepRiseG", { it.stepRiseG }) { p, v -> p.copy(stepRiseG = v) },
        double("stepResetG", { it.stepResetG }) { p, v -> p.copy(stepResetG = v) },
        long("stepMinIntervalMs", { it.stepMinIntervalMs }) { p, v -> p.copy(stepMinIntervalMs = v) },
        long("watchMaxGapMs", { it.watchMaxGapMs }) { p, v -> p.copy(watchMaxGapMs = v) },
    )
}

private val TRACKING_FIELDS: List<Field<TrackingParams>> = fieldsOf {
    listOf(
        long("windowMs", { it.windowMs }) { p, v -> p.copy(windowMs = v) },
        double("processNoise", { it.processNoise }) { p, v -> p.copy(processNoise = v) },
        double("sigmaRangeM", { it.sigmaRangeM }) { p, v -> p.copy(sigmaRangeM = v) },
        double("sigmaAngleDeg", { it.sigmaAngleDeg }) { p, v -> p.copy(sigmaAngleDeg = v) },
        double("maxAngleForNoiseDeg", { it.maxAngleForNoiseDeg }) { p, v -> p.copy(maxAngleForNoiseDeg = v) },
        double("overlapNoiseFactor", { it.overlapNoiseFactor }) { p, v -> p.copy(overlapNoiseFactor = v) },
        double("sigmaInitialSpeedMps", { it.sigmaInitialSpeedMps }) { p, v -> p.copy(sigmaInitialSpeedMps = v) },
        double("gateChi2", { it.gateChi2 }) { p, v -> p.copy(gateChi2 = v) },
        double("gateConfirmedM", { it.gateConfirmedM }) { p, v -> p.copy(gateConfirmedM = v) },
        double("gateTentativeM", { it.gateTentativeM }) { p, v -> p.copy(gateTentativeM = v) },
        int("confirmHits", { it.confirmHits }) { p, v -> p.copy(confirmHits = v) },
        int("confirmWindows", { it.confirmWindows }) { p, v -> p.copy(confirmWindows = v) },
        long("stopScanTailMs", { it.stopScanTailMs }) { p, v -> p.copy(stopScanTailMs = v) },
        int("tentativeMaxMisses", { it.tentativeMaxMisses }) { p, v -> p.copy(tentativeMaxMisses = v) },
        long("tentativeMaxSilenceMs", { it.tentativeMaxSilenceMs }) { p, v -> p.copy(tentativeMaxSilenceMs = v) },
        double("stillSpeedMps", { it.stillSpeedMps }) { p, v -> p.copy(stillSpeedMps = v) },
        long("coastStillMs", { it.coastStillMs }) { p, v -> p.copy(coastStillMs = v) },
        long("coastMovingMs", { it.coastMovingMs }) { p, v -> p.copy(coastMovingMs = v) },
        long("coastExitMs", { it.coastExitMs }) { p, v -> p.copy(coastExitMs = v) },
        long("outOfViewMs", { it.outOfViewMs }) { p, v -> p.copy(outOfViewMs = v) },
        double("coastGateSigmaM", { it.coastGateSigmaM }) { p, v -> p.copy(coastGateSigmaM = v) },
        double("coastSpeedTauS", { it.coastSpeedTauS }) { p, v -> p.copy(coastSpeedTauS = v) },
        double("inheritDistanceM", { it.inheritDistanceM }) { p, v -> p.copy(inheritDistanceM = v) },
        long("inheritMaxAgeMs", { it.inheritMaxAgeMs }) { p, v -> p.copy(inheritMaxAgeMs = v) },
    )
}

private val ALERT_FIELDS: List<Field<AlertParams>> = fieldsOf {
    listOf(
        double("centerHalfWidthDeg", { it.centerHalfWidthDeg }) { p, v -> p.copy(centerHalfWidthDeg = v) },
        long("minGapMs", { it.minGapMs }) { p, v -> p.copy(minGapMs = v) },
        long("sectorPauseMs", { it.sectorPauseMs }) { p, v -> p.copy(sectorPauseMs = v) },
        double("reacquireDistanceM", { it.reacquireDistanceM }) { p, v -> p.copy(reacquireDistanceM = v) },
        int("maxPerMinute", { it.maxPerMinute }) { p, v -> p.copy(maxPerMinute = v) },
        long("systemPatternMs", { it.systemPatternMs }) { p, v -> p.copy(systemPatternMs = v) },
    )
}

private val CLOCK_FIELDS: List<Field<ClockParams>> = fieldsOf {
    listOf(
        long("windowMs", { it.windowMs }) { p, v -> p.copy(windowMs = v) },
        int("maxWindows", { it.maxWindows }) { p, v -> p.copy(maxWindows = v) },
        double("maxDriftPpm", { it.maxDriftPpm }) { p, v -> p.copy(maxDriftPpm = v) },
    )
}

private val STATUS_FIELDS: List<Field<StatusParams>> = fieldsOf {
    listOf(
        long("linkStaleMs", { it.linkStaleMs }) { p, v -> p.copy(linkStaleMs = v) },
        long("corruptWindowMs", { it.corruptWindowMs }) { p, v -> p.copy(corruptWindowMs = v) },
        int("corruptEventsThreshold", { it.corruptEventsThreshold }) { p, v -> p.copy(corruptEventsThreshold = v) },
    )
}

private val MOUNT_FIELDS: List<Field<RadarMount>> = fieldsOf {
    listOf(
        int("radarId", { it.radarId }) { p, v -> p.copy(radarId = v) },
        double("xM", { it.xM }) { p, v -> p.copy(xM = v) },
        double("yM", { it.yM }) { p, v -> p.copy(yM = v) },
        double("yawDeg", { it.yawDeg }) { p, v -> p.copy(yawDeg = v) },
        boolean("flipX", { it.flipX }) { p, v -> p.copy(flipX = v) },
        int("speedSign", { it.speedSign }) { p, v -> p.copy(speedSign = v) },
    )
}
