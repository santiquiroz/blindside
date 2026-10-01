package io.github.santiquiroz.blindside.wear.session

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.wear.BuildConfig
import io.github.santiquiroz.blindside.wear.ble.BeltLink
import io.github.santiquiroz.blindside.wear.demo.DemoSource
import io.github.santiquiroz.blindside.wear.demo.demoPackets
import io.github.santiquiroz.blindside.wear.haptics.HapticPlayer
import io.github.santiquiroz.blindside.wear.haptics.dndMaySilenceNow
import io.github.santiquiroz.blindside.wear.haptics.millisUntil
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_ACTIVITY_RECOGNITION
import io.github.santiquiroz.blindside.wear.recording.InfoHeaderSink
import io.github.santiquiroz.blindside.wear.recording.RecordSink
import io.github.santiquiroz.blindside.wear.recording.RecordingMeta
import io.github.santiquiroz.blindside.wear.recording.SessionClockStamp
import io.github.santiquiroz.blindside.wear.recording.headerJson
import io.github.santiquiroz.blindside.wear.recording.openRecordingSink
import io.github.santiquiroz.blindside.wear.recording.recordingFileName
import io.github.santiquiroz.blindside.wear.recording.recordingMeta
import io.github.santiquiroz.blindside.wear.recording.recordingsDir
import io.github.santiquiroz.blindside.wear.sensors.WatchSensors
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.settings.SettingsRepository
import io.github.santiquiroz.blindside.wear.settings.forNewSession
import io.github.santiquiroz.blindside.wear.settings.toPipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

private const val TAG = "BlindsideSession"
private const val FLUSH_EVERY_MS = 2_000L

private fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

@OptIn(ExperimentalCoroutinesApi::class)
class RunningSession(
    private val context: Context,
    private val source: SessionSource,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val useWakeLock: Boolean,
) {
    private val pipelineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)
    private val wakeLock = SessionWakeLock(context)
    private val sensors = WatchSensors(context.getSystemService(SensorManager::class.java), SensorInputs(inputs))
    private val loops = mutableListOf<Job>()
    private var consumer: Job? = null
    private var belt: BeltLink? = null

    @Volatile
    private var screenMode = ScreenMode.SIGILO

    suspend fun start() {
        settings.update { it.forNewSession() }
        val initial = settings.current()
        flagDndRisk(initial)
        consumer = scope.launch(pipelineDispatcher) { consume(createEngine(initial)) }
        holdWakeLock()
        launchLoops()
        startSensors()
        startSource(initial)
    }

    fun mark() {
        inputs.trySend(SessionInput.Marker(nowNanos()))
    }

    fun retryLink() {
        belt?.retry()
    }

    suspend fun stop() {
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

    private fun createEngine(initial: AppSettings): SessionEngine {
        val startNanos = nowNanos()
        val haptics = HapticPlayer.create(context, initial.vibrationUsage)
        return SessionEngine(
            pipeline = RadarPipelineAdapter(RadarPipeline(toPipelineConfig(initial))),
            records = openRecorder(initial, startNanos, haptics),
            haptics = haptics,
            scenes = SceneSink { scene -> SessionStore.update { it.copy(scene = scene) } },
            clock = NanoClock(::nowNanos),
            startNanos = startNanos,
            deferred = DeferredPlayback(::playLater),
            onError = { error -> Log.w(TAG, "session input failed", error) },
        )
    }

    private fun playLater(alert: ContactAlert, atNanos: Long) {
        scope.launch {
            delay(millisUntil(atNanos, nowNanos()))
            inputs.trySend(SessionInput.PlayDeferred(alert, atNanos))
        }
    }

    private fun openRecorder(initial: AppSettings, startNanos: Long, haptics: HapticPlayer): RecordSink {
        val stamp = SessionClockStamp(epochMs = System.currentTimeMillis(), elapsedNanos = startNanos)
        val name = recordingFileName(stamp.epochMs, ZoneId.systemDefault(), source.name)
        val meta = recordingMeta(
            initial, source.name, stamp, Build.MODEL, BuildConfig.VERSION_NAME,
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

    private fun launchLoops() {
        loops += scope.launch { repeatEvery(FLUSH_EVERY_MS) { inputs.trySend(SessionInput.Flush) } }
        loops += scope.launch { tickScenes() }
        loops += scope.launch { forwardMode() }
    }

    private suspend fun repeatEvery(periodMs: Long, action: () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay(periodMs)
            action()
        }
    }

    private suspend fun tickScenes() {
        while (currentCoroutineContext().isActive) {
            val period = scenePeriodMs(SessionStore.radarVisible.value, screenMode, SessionStore.ambient.value)
            if (period != null) inputs.trySend(SessionInput.Tick(nowNanos()))
            delay(period ?: IDLE_TICK_POLL_MS)
        }
    }

    private suspend fun forwardMode() {
        modeChanges().collect { (eliminated, mode) ->
            screenMode = mode
            inputs.trySend(SessionInput.ModeChanged(eliminated, mode, nowNanos()))
        }
    }

    private fun modeChanges(): Flow<Pair<Boolean, ScreenMode>> =
        combine(SessionStore.state.map { it.eliminated }, settings.settings.map { it.screenMode }) { eliminated, mode ->
            eliminated to mode
        }.distinctUntilChanged()

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
        belt = BeltLink(context, BeltInputs(inputs), onBeltFound = ::rememberBelt).also { it.start(savedAddress) }
    }

    private fun rememberBelt(address: String) {
        scope.launch { settings.update { it.copy(beltAddress = address) } }
    }
}
