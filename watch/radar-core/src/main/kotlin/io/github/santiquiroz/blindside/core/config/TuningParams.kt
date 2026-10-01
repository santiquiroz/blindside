package io.github.santiquiroz.blindside.core.config

data class DecodeParams(
    val maxAbsAngleDeg: Double = 65.0,
    val maxSpeedMps: Double = 10.0,
    val validResolutionsMm: Set<Int> = setOf(320, 360),
    val staleRepeatFrames: Int = 3,
    val nearFieldM: Double = 0.8,
    val coneHalfAngleDeg: Double = 60.0,
    val maxRangeM: Double = 6.0,
)

data class ImuParams(
    val bootWindowSamples: Int = 100,
    val restWindowSamples: Int = 150,
    val stillMaxSpreadDps: Double = 3.0,
    val stillMaxAccelStdG: Double = 0.02,
    val defectiveMeanDps: Double = 45.0,
    val reseedMinOffsetDps: Double = 3.0,
    val restBiasTauS: Double = 20.0,
    val witnessMaxDps: Double = 3.0,
    val witnessMinSamples: Int = 5,
    val witnessHistoryMs: Long = 5000,
    val gravityTauS: Double = 1.0,
    val gravityRejectG: Double = 0.15,
    val proneAngleDeg: Double = 60.0,
    val gyroScale: Double = 1.0,
    val radarImuDelayMs: Long = 100,
    val yawHistoryMs: Long = 3000,
    val yawExtrapolationMaxMs: Long = 150,
    val yawRateSamplesForExtrapolation: Int = 3,
    val yawBlendTauMs: Double = 50.0,
)

data class MotionParams(
    val turningRateDps: Double = 20.0,
    val turningTauS: Double = 0.2,
    val walkingAccelStdG: Double = 0.08,
    val walkingWindowMs: Long = 1000,
    val stepHoldMs: Long = 1200,
    val stepRiseG: Double = 0.12,
    val stepResetG: Double = 0.03,
    val stepMinIntervalMs: Long = 250,
    val watchMaxGapMs: Long = 500,
)

data class TrackingParams(
    val windowMs: Long = 100,
    val processNoise: Double = 0.4,
    val sigmaRangeM: Double = 0.2,
    val sigmaAngleDeg: Double = 3.5,
    val maxAngleForNoiseDeg: Double = 70.0,
    val overlapNoiseFactor: Double = 1.5,
    val sigmaInitialSpeedMps: Double = 1.5,
    val gateChi2: Double = 9.21,
    val gateConfirmedM: Double = 1.5,
    val gateTentativeM: Double = 1.0,
    val confirmHits: Int = 3,
    val confirmWindows: Int = 5,
    val stopScanTailMs: Long = 500,
    val tentativeMaxMisses: Int = 2,
    val tentativeMaxSilenceMs: Long = 1000,
    val stillSpeedMps: Double = 0.3,
    val coastStillMs: Long = 6000,
    val coastMovingMs: Long = 1500,
    val coastExitMs: Long = 300,
    val outOfViewMs: Long = 5000,
    val coastGateSigmaM: Double = 0.7,
    val coastSpeedTauS: Double = 1.0,
    val inheritDistanceM: Double = 1.0,
    val inheritMaxAgeMs: Long = 2000,
)

data class AlertParams(
    val centerHalfWidthDeg: Double = 20.0,
    val minGapMs: Long = 1000,
    val sectorPauseMs: Long = 5000,
    val reacquireDistanceM: Double = 1.5,
    val maxPerMinute: Int = 10,
    val systemPatternMs: Long = 1200,
)

data class ClockParams(
    val windowMs: Long = 10_000,
    val maxWindows: Int = 6,
    val maxDriftPpm: Double = 200.0,
)

data class StatusParams(
    val linkStaleMs: Long = 1000,
    val corruptWindowMs: Long = 10_000,
    val corruptEventsThreshold: Int = 20,
)

data class TuningParams(
    val decode: DecodeParams = DecodeParams(),
    val imu: ImuParams = ImuParams(),
    val motion: MotionParams = MotionParams(),
    val tracking: TrackingParams = TrackingParams(),
    val alerts: AlertParams = AlertParams(),
    val clock: ClockParams = ClockParams(),
    val status: StatusParams = StatusParams(),
)
