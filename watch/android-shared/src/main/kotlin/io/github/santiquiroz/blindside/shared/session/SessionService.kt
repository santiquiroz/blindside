package io.github.santiquiroz.blindside.shared.session

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.shared.settings.settingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "BlindsideService"

// RunningSession launches in this scope too, so one failed task is logged instead of killing the whole game.
private val logFailedTask = CoroutineExceptionHandler { _, error -> Log.e(TAG, "session task failed", error) }

private data class ServiceCommand(val intent: Intent?, val startId: Int)

private fun logFailedCommand(command: ServiceCommand, error: Exception) {
    Log.e(TAG, "command ${command.intent?.action} failed", error)
}

abstract class SessionService : Service() {
    protected abstract val host: SessionHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailedTask)
    private var session: RunningSession? = null
    private var currentSource = SessionSource.BELT
    private var currentPurpose = SessionPurpose.GAME
    private var notificationSync: Job? = null
    private var companions: List<Job> = emptyList()
    private val commands = SerialCommands(scope, ::logFailedCommand, ::handle)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        commands.submit(ServiceCommand(intent, startId))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        commands.close()
        scope.cancel()
        super.onDestroy()
    }

    // stopSelf(startId) keeps the service when a newer command is already queued: that command decides instead.
    private suspend fun handle(command: ServiceCommand) {
        val intent = command.intent
        val startId = command.startId
        when (intent?.action) {
            SessionActions.ACTION_START -> onStart(sourceOf(intent), intent.getBooleanExtra(SessionActions.EXTRA_WAKE_LOCK, true), purposeOf(intent), startId)
            SessionActions.ACTION_STOP -> onStop(startId)
            SessionActions.ACTION_MARKER -> session?.mark() ?: stopSelf(startId)
            SessionActions.ACTION_RETRY_LINK -> session?.retryLink() ?: stopSelf(startId)
            SessionActions.ACTION_TOGGLE_ELIMINATED -> if (session != null) toggleEliminated(scope, settingsRepository()) else stopSelf(startId)
            SessionActions.ACTION_OPEN_PAIRING -> requestPairingWindow(startId)
            SessionActions.ACTION_SEND_COMMAND -> sendCommand(intent, startId)
            SessionActions.ACTION_REFRESH_INFO -> refreshInfo(startId)
            else -> stopIfIdle(startId)
        }
    }

    private fun sourceOf(intent: Intent): SessionSource = sourceFrom(intent.getStringExtra(SessionActions.EXTRA_SOURCE))

    private fun purposeOf(intent: Intent): SessionPurpose = purposeFrom(intent.getStringExtra(SessionActions.EXTRA_PURPOSE))

    private suspend fun onStart(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose, startId: Int) {
        when (startTransition(session?.let { currentPurpose }, purpose)) {
            StartTransition.KEEP -> goForeground(currentSource)
            StartTransition.START -> startIfAllowed(source, wakeLock, purpose, startId)
            StartTransition.RESTART -> restartAs(source, wakeLock, purpose, startId)
        }
    }

    private fun startIfAllowed(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose, startId: Int) {
        val blocker = startBlocker(source, hasBluetoothPermissions(), hasBluetoothAdapter())
        if (blocker == null) beginSession(source, wakeLock, purpose) else rejectStart(blocker, startId)
    }

    private fun beginSession(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose) {
        currentSource = source
        currentPurpose = purpose
        goForeground(source)
        SessionStore.update { startedState(it, source, purpose, SystemClock.elapsedRealtime()) }
        val created = RunningSession(this, source, settingsRepository(), scope, wakeLock, host, purpose)
        session = created
        created.begin()
        notificationSync = scope.launch { syncNotification(source, purpose) }
        companions = host.launchCompanions(this, scope)
    }

    // A purpose change (diagnostic and game) keeps the foreground service: the old link closes before the new one opens.
    private suspend fun restartAs(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose, startId: Int) {
        detachSession()?.stop()
        startIfAllowed(source, wakeLock, purpose, startId)
    }

    private suspend fun onStop(startId: Int) {
        val current = detachSession() ?: return stopSelf(startId)
        current.stop()
        SessionStore.update(::stoppedState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun detachSession(): RunningSession? {
        val current = session ?: return null
        session = null
        notificationSync?.cancel()
        companions.forEach { it.cancel() }
        companions = emptyList()
        return current
    }

    private fun requestPairingWindow(startId: Int) {
        val queued = session?.openPairingWindow() ?: false
        SessionStore.update { it.copy(phonePairing = pairingAfterRequest(queued)) }
        stopIfIdle(startId)
    }

    private fun sendCommand(intent: Intent, startId: Int) {
        val current = session ?: return stopSelf(startId)
        val command = commandFrom(commandExtrasOf(intent))
        if (command == null || !current.send(command)) Log.w(TAG, "command not sent: ${intent.getStringExtra(SessionActions.EXTRA_COMMAND)}")
    }

    private fun refreshInfo(startId: Int) {
        val current = session ?: return stopSelf(startId)
        if (!current.refreshInfo()) Log.w(TAG, "info refresh skipped: link not streaming")
    }

    private fun commandExtrasOf(intent: Intent): CommandExtras = CommandExtras(
        intent.getStringExtra(SessionActions.EXTRA_COMMAND).orEmpty(),
        intent.getIntExtra(SessionActions.EXTRA_RADAR_ID, NO_RADAR_ID),
    )

    private fun rejectStart(error: StartError, startId: Int) {
        SessionStore.update { blockedState(it, error) }
        answerForegroundStart()
        stopSelf(startId)
    }

    // startForegroundService() must be answered with startForeground(); a host whose only type needs Bluetooth may fail here, which is logged.
    private fun answerForegroundStart() {
        runCatching { goForeground(SessionSource.DEMO) }.onFailure { Log.w(TAG, "could not answer the foreground start", it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopIfIdle(startId: Int) {
        if (session == null) stopSelf(startId)
    }

    private fun goForeground(source: SessionSource) {
        host.ensureNotificationChannel(this)
        val notification = host.notification(this, ongoingStatus(eliminated = false, source = source, purpose = currentPurpose))
        startForeground(host.notificationId, notification, host.foregroundTypes(source))
    }

    private suspend fun syncNotification(source: SessionSource, purpose: SessionPurpose) {
        SessionStore.state.map { it.eliminated }.distinctUntilChanged().collect { eliminated ->
            val notification = host.notification(this, ongoingStatus(eliminated, source, purpose))
            getSystemService(NotificationManager::class.java).notify(host.notificationId, notification)
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        listOf(PERMISSION_BLUETOOTH_SCAN, PERMISSION_BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasBluetoothAdapter(): Boolean = getSystemService(BluetoothManager::class.java)?.adapter != null
}
