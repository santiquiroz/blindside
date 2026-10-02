package io.github.santiquiroz.blindside.shared.session

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.ble.BeltLink
import io.github.santiquiroz.blindside.shared.demo.DemoSource
import io.github.santiquiroz.blindside.shared.demo.demoPackets
import io.github.santiquiroz.blindside.shared.haptics.HapticPlayer
import io.github.santiquiroz.blindside.shared.haptics.SYSTEM_PATTERN
import io.github.santiquiroz.blindside.shared.haptics.dndMaySilenceNow
import io.github.santiquiroz.blindside.shared.haptics.millisUntil
import io.github.santiquiroz.blindside.shared.hud.FIVE_MIN_MS
import io.github.santiquiroz.blindside.shared.hud.crossedThreshold
import io.github.santiquiroz.blindside.shared.hud.gameRemainingMs
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_ACTIVITY_RECOGNITION
import io.github.santiquiroz.blindside.shared.recording.InfoHeaderSink
import io.github.santiquiroz.blindside.shared.recording.NoOpRecordSink
import io.github.santiquiroz.blindside.shared.recording.RecordSink
import io.github.santiquiroz.blindside.shared.recording.RecordingMeta
import io.github.santiquiroz.blindside.shared.recording.SessionClockStamp
import io.github.santiquiroz.blindside.shared.recording.headerJson
import io.github.santiquiroz.blindside.shared.recording.openRecordingSink
import io.github.santiquiroz.blindside.shared.recording.recordingFileName
import io.github.santiquiroz.blindside.shared.recording.recordingMeta
import io.github.santiquiroz.blindside.shared.recording.recordingsDir
import io.github.santiquiroz.blindside.shared.sensors.DeviceSensors
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.toPipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

private const val TAG = "BlindsideSession"
private const val FLUSH_EVERY_MS = 2_000L
private const val GAME_CLOCK_POLL_MS = 1_000L

private fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

@OptIn(ExperimentalCoroutinesApi::class)
class RunningSession(
    private val context: Context,
    private val source: SessionSource,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val useWakeLock: Boolean,
    private val host: SessionHost,
    private val purpose: SessionPurpose = SessionPurpose.GAME,
) {
    private val pipelineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)
    private val wakeLock = SessionWakeLock(context)
    private val sensors = DeviceSensors(context.getSystemService(SensorManager::class.java), SensorInputs(inputs))
    private val loops = mutableListOf<Job>()
    private var consumer: Job? = null
    private var startJob: Job? = null
    private var belt: BeltLink? = null
    private var traits = SessionTraits(host.beltProfile)

    @Volatile
    private var screenMode = ScreenMode.SIGILO

    fun begin() {
        startJob = scope.launch { start() }
    }

    private suspend fun start() {
        val initial = settings.current()
        traits = host.traitsFor(context, purpose)
        flagDndRisk(initial)
        val player = HapticPlayer.create(context, initial.vibrationUsage)
        consumer = scope.launch(pipelineDispatcher) { consume(createEngine(initial, player)) }
        holdWakeLock()
        launchLoops(initial, player)
        if (traits.readsDeviceSensors) startSensors()
        startSource(initial)
    }

    fun mark() {
        inputs.trySend(SessionInput.Marker(nowNanos()))
    }

    fun retryLink() {
        belt?.retry()
    }

    fun openPairingWindow(): Boolean = belt?.send(BeltCommand.OpenPairingWindow) ?: false

    fun send(command: BeltCommand): Boolean = belt?.send(command) ?: false

    fun refreshInfo(): Boolean = belt?.refreshInfo() ?: false

    // A stop that lands while start() still awaits the settings store cancels it there, before any link or loop exists.
    suspend fun stop() {
        startJob?.cancelAndJoin()
        belt?.stop()
        sensors.stop()
        loops.forEach { it.cancel() }
        inputs.close()
        consumer?.join()
        wakeLock.release()
    }

    private suspend fun consume(engine: SessionEngine) {
        try {
            for (input in inputs) engine.handle(input)
        } finally {
            engine.close()
        }
    }

    private fun createEngine(initial: AppSettings, player: HapticPlayer): SessionEngine {
        val startNanos = nowNanos()
        val pipeline = RadarPipeline(toPipelineConfig(initial))
        return SessionEngine(
            pipeline = RadarPipelineAdapter(pipeline),
            records = openRecorder(initial, startNanos, player),
            haptics = hapticsFor(traits, player),
            scenes = SceneSink { scene -> publish(scene, pipeline) },
            clock = NanoClock(::nowNanos),
            startNanos = startNanos,
            deferred = DeferredPlayback(::playLater),
            onError = { error -> Log.w(TAG, "session input failed", error) },
        )
    }

    // SceneSink runs on the pipeline thread inside the tick, so reading the counters cannot race a packet.
    private fun publish(scene: RadarScene, pipeline: RadarPipeline) {
        SessionStore.update { it.copy(scene = scene) }
        host.onScene(pipeline.counters())
    }

    private fun playLater(alert: ContactAlert, atNanos: Long) {
        scope.launch {
            delay(millisUntil(atNanos, nowNanos()))
            inputs.trySend(SessionInput.PlayDeferred(alert, atNanos))
        }
    }

    private fun openRecorder(initial: AppSettings, startNanos: Long, haptics: HapticPlayer): RecordSink {
        if (!traits.records) return NoOpRecordSink
        val stamp = SessionClockStamp(epochMs = System.currentTimeMillis(), elapsedNanos = startNanos)
        val name = recordingFileName(stamp.epochMs, ZoneId.systemDefault(), recordingTagFor(traits, source))
        val meta = recordingMeta(
            initial, source.name, stamp, Build.MODEL, host.appVersion,
            haptics.hasAmplitudeControl(), haptics.supportsPrimitives(),
        )
        SessionStore.update { it.copy(recordingName = name, recordingFailed = false) }
        val open = { info: String? -> openRecording(name, meta.copy(infoJson = info)) }
        return if (source == SessionSource.BELT) InfoHeaderSink(open) else open(null)
    }

    private fun openRecording(name: String, meta: RecordingMeta): RecordSink =
        openRecordingSink(recordingsDir(context), name, headerJson(meta), ::onRecordingFailed)

    private fun onRecordingFailed(error: Exception) {
        Log.e(TAG, "recording failed", error)
        SessionStore.update { it.copy(recordingFailed = true) }
    }

    private fun holdWakeLock() {
        if (!useWakeLock) return
        wakeLock.acquireOrRenew()
        loops += scope.launch { repeatEvery(SessionWakeLock.RENEW_EVERY_MS) { wakeLock.acquireOrRenew() } }
    }

    private fun launchLoops(initial: AppSettings, player: HapticPlayer) {
        loops += scope.launch { repeatEvery(FLUSH_EVERY_MS) { inputs.trySend(SessionInput.Flush) } }
        loops += scope.launch { tickScenes() }
        loops += scope.launch { forwardMode() }
        if (purpose == SessionPurpose.GAME && initial.gameDurationMs > 0L) {
            loops += scope.launch { tickGameClock(initial.gameDurationMs, player) }
        }
    }

    // The game clock pulses from the service, not the Compose frame clock, so 5-min and end buzzes still fire in Sigilo.
    private suspend fun tickGameClock(durationMs: Long, player: HapticPlayer) {
        var previousRemaining = durationMs
        while (currentCoroutineContext().isActive) {
            delay(GAME_CLOCK_POLL_MS)
            val start = SessionStore.state.value.gameStartElapsedMs ?: continue
            val remaining = gameRemainingMs(start, SystemClock.elapsedRealtime(), durationMs)
            if (gameClockPulses(previousRemaining, remaining)) player.play(SYSTEM_PATTERN)
            previousRemaining = remaining
        }
    }

    private fun gameClockPulses(previousRemaining: Long, remaining: Long): Boolean =
        crossedThreshold(previousRemaining, remaining, FIVE_MIN_MS) || crossedThreshold(previousRemaining, remaining, 0L)

    private suspend fun repeatEvery(periodMs: Long, action: () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay(periodMs)
            action()
        }
    }

    private suspend fun tickScenes() {
        while (currentCoroutineContext().isActive) {
            val period = pacedPeriodMs(traits.framePeriodMs, SessionStore.radarVisible.value, screenMode, SessionStore.ambient.value)
            if (period != null) inputs.trySend(SessionInput.Tick(nowNanos()))
            delay(period ?: IDLE_TICK_POLL_MS)
        }
    }

    private suspend fun forwardMode() {
        settings.settings.map { it.screenMode }.distinctUntilChanged().collect { mode ->
            screenMode = mode
            inputs.trySend(SessionInput.ModeChanged(mode, nowNanos()))
        }
    }

    private fun flagDndRisk(initial: AppSettings) {
        val atRisk = dndMaySilenceNow(context, initial.vibrationUsage)
        SessionStore.update { it.copy(dndMaySilenceAlerts = atRisk) }
    }

    private fun startSensors() {
        val availability = sensors.start(stepsAllowed = hasActivityRecognition())
        SessionStore.update { it.copy(watchSteps = availability.steps) }
    }

    private fun hasActivityRecognition(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun startSource(initial: AppSettings) {
        when (source) {
            SessionSource.BELT -> startBelt(initial.beltAddress)
            SessionSource.DEMO -> loops += scope.launch(Dispatchers.Default) {
                DemoSource(demoPackets(), inputs, ::nowNanos).run()
            }
        }
    }

    private fun startBelt(savedAddress: String?) {
        val listener = host.beltListener(BeltInputs(inputs))
        belt = BeltLink(context, listener, traits.link, onBeltFound = ::rememberBelt).also { it.start(savedAddress) }
    }

    private fun rememberBelt(address: String) {
        scope.launch { settings.update { it.copy(beltAddress = address) } }
    }
}
