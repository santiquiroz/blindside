package io.github.santiquiroz.blindside.wear.session

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
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

class BlindsideSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailedTask)
    private var session: RunningSession? = null
    private var currentSource = SessionSource.BELT
    private var notificationSync: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SessionCommands.ACTION_START -> onStart(sourceOf(intent), intent.getBooleanExtra(SessionCommands.EXTRA_WAKE_LOCK, true))
            SessionCommands.ACTION_STOP -> onStop()
            SessionCommands.ACTION_MARKER -> session?.mark() ?: stopSelf()
            SessionCommands.ACTION_RETRY_LINK -> session?.retryLink() ?: stopSelf()
            SessionCommands.ACTION_TOGGLE_ELIMINATED -> if (session != null) toggleEliminated(scope, settingsRepository()) else stopSelf()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sourceOf(intent: Intent): SessionSource = sourceFrom(intent.getStringExtra(SessionCommands.EXTRA_SOURCE))

    private fun onStart(source: SessionSource, wakeLock: Boolean) {
        if (session != null) {
            goForeground(currentSource)
            return
        }
        val blocker = startBlocker(source, hasBluetoothPermissions(), hasBluetoothAdapter())
        if (blocker != null) {
            rejectStart(blocker)
            return
        }
        beginSession(source, wakeLock)
    }

    private fun beginSession(source: SessionSource, wakeLock: Boolean) {
        currentSource = source
        goForeground(source)
        SessionStore.update { startedState(it, source) }
        val created = RunningSession(this, source, settingsRepository(), scope, wakeLock)
        session = created
        scope.launch { created.start() }
        notificationSync = scope.launch { syncNotification(source) }
    }

    private fun onStop() {
        val current = session ?: return stopSelf()
        session = null
        notificationSync?.cancel()
        scope.launch {
            current.stop()
            SessionStore.update(::stoppedState)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun rejectStart(error: StartError) {
        SessionStore.update { blockedState(it, error) }
        answerForegroundStart()
        stopSelf()
    }

    // startForegroundService() must be answered with startForeground() even when the start is refused, or Android kills the app.
    private fun answerForegroundStart() {
        goForeground(SessionSource.DEMO)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopIfIdle() {
        if (session == null) stopSelf()
    }

    private fun goForeground(source: SessionSource) {
        SessionNotification.ensureChannel(this)
        val notification = SessionNotification.build(this, ongoingStatus(eliminated = false, source = source))
        startForeground(SessionNotification.NOTIFICATION_ID, notification, foregroundTypesFor(source))
    }

    private suspend fun syncNotification(source: SessionSource) {
        SessionStore.state.map { it.eliminated }.distinctUntilChanged().collect { eliminated ->
            val notification = SessionNotification.build(this, ongoingStatus(eliminated, source))
            getSystemService(NotificationManager::class.java).notify(SessionNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        listOf(PERMISSION_BLUETOOTH_SCAN, PERMISSION_BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasBluetoothAdapter(): Boolean = getSystemService(BluetoothManager::class.java)?.adapter != null
}
