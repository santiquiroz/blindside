package io.github.santiquiroz.blindside.phone.tak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import io.github.santiquiroz.blindside.phone.BuildConfig
import io.github.santiquiroz.blindside.phone.MainActivity
import io.github.santiquiroz.blindside.phone.R
import io.github.santiquiroz.blindside.shared.tak.GeoFix
import io.github.santiquiroz.blindside.shared.tak.TAK_TEAM_PATH
import io.github.santiquiroz.blindside.shared.tak.TAK_TEAM_PERIOD_MS
import io.github.santiquiroz.blindside.shared.tak.TAK_TELEMETRY_PATH
import io.github.santiquiroz.blindside.shared.tak.TeamUpdate
import io.github.santiquiroz.blindside.shared.tak.decodeTelemetry
import io.github.santiquiroz.blindside.shared.tak.encodeTeamUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

private const val TAG = "TakLinkService"
private const val TEAM_FIX_FRESH_MS = 15_000L

class TakLinkService : Service() {
    companion object {
        const val ACTION_START = "io.github.santiquiroz.blindside.phone.tak.START"
        const val ACTION_STOP = "io.github.santiquiroz.blindside.phone.tak.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TakLinkService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TakLinkService::class.java).setAction(ACTION_STOP))
        }
    }

    private var scope: CoroutineScope? = null
    private var link: TakLink? = null
    private var location: TakLocation? = null
    private var listener: MessageClient.OnMessageReceivedListener? = null
    private val mutex = Mutex()
    private var publish = PublishState()
    private var roster = TeamRoster()
    private var fix: GeoFix? = null
    private var fixAtMs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startLink()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startLink() {
        if (scope != null) return
        TakLinkNotification.ensureChannel(this)
        TakStore.update { TakUiState(running = true) }
        if (!enterForeground()) return
        val current = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = current
        current.launch { boot() }
    }

    // Android 14+ throws when a location service starts without the location permission.
    private fun enterForeground(): Boolean = try {
        startForeground(
            TakLinkNotification.NOTIFICATION_ID,
            TakLinkNotification.build(this, takStatusText(TakStore.state.value, System.currentTimeMillis())),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (error: SecurityException) {
        TakStore.update { it.copy(running = false, error = "Falta permiso de ubicación") }
        stopSelf()
        false
    }

    private suspend fun boot() {
        val prefs = applicationContext.takPrefsRepository().current()
        val pkg = loadTakPackage(this)
        if (pkg == null) {
            TakStore.update { it.copy(error = "Falta el paquete TAK") }
            stopSelf()
            return
        }
        val ids = TakIds(prefs.deviceId.ifEmpty { deviceIdOf(pkg.clientName) }, prefs.callsign.ifBlank { pkg.clientName })
        val tls = try {
            sslContextOf(pkg)
        } catch (error: TakSetupException) {
            TakStore.update { it.copy(error = error.message) }
            stopSelf()
            return
        }
        val takLink = TakLink(tlsConnector(pkg, tls), ids, BuildConfig.VERSION_NAME)
        link = takLink
        val current = scope ?: return
        current.launch { takLink.run() }
        current.launch { mirrorStatus(takLink) }
        current.launch { collectIncoming(takLink, ids.callsign) }
        startGps()
        addTelemetryListener(ids)
        current.launch { teamLoop() }
    }

    private fun startGps() {
        val gps = TakLocation(this) { fresh, atMs ->
            scope?.launch { mutex.withLock { fix = fresh; fixAtMs = atMs } }
        }
        location = gps
        if (!gps.start()) TakStore.update { it.copy(error = "Falta permiso de ubicación") }
    }

    private fun addTelemetryListener(ids: TakIds) {
        val onMessage = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == TAK_TELEMETRY_PATH) scope?.launch { handleTelemetry(event.data, ids) }
        }
        listener = onMessage
        Wearable.getMessageClient(this).addListener(onMessage)
    }

    private suspend fun handleTelemetry(data: ByteArray, ids: TakIds) {
        val telemetry = decodeTelemetry(data.toString(Charsets.UTF_8)) ?: return
        val publishContacts = applicationContext.takPrefsRepository().current().publishContacts
        val now = System.currentTimeMillis()
        val sent = mutex.withLock {
            val outgoing = publishTelemetry(publish, telemetry, fix, fixAtMs?.let { now - it }, ids, publishContacts, now)
            publish = outgoing.state
            outgoing.events.forEach { link?.send(it) }
            outgoing.state.publishedUids.size
        }
        if (sent > 0) TakStore.update { it.copy(contactsSent = it.contactsSent + sent) }
    }

    private suspend fun collectIncoming(takLink: TakLink, ownCallsign: String) {
        takLink.incoming.collect { event ->
            mutex.withLock { roster = roster.with(event, ownCallsign, System.currentTimeMillis()) }
        }
    }

    private suspend fun mirrorStatus(takLink: TakLink) {
        takLink.status.collect { status -> TakStore.update { it.copy(link = status) } }
    }

    private suspend fun teamLoop() {
        val nodes = Wearable.getNodeClient(this)
        val messages = Wearable.getMessageClient(this)
        while (true) {
            delay(TAK_TEAM_PERIOD_MS)
            val now = System.currentTimeMillis()
            val team = mutex.withLock { teamSnapshot(now) }
            sendTeam(nodes, messages, team.update)
            TakStore.update { it.copy(mates = team.count, lastFixAtMs = team.fixAtMs) }
            refreshNotification()
        }
    }

    private fun teamSnapshot(now: Long): TeamSnapshot {
        val fresh = if (fix != null && fixAtMs != null && now - fixAtMs!! <= TEAM_FIX_FRESH_MS) fix else null
        val mates = roster.mates(now)
        return TeamSnapshot(TeamUpdate(fresh, mates), mates.size, fixAtMs)
    }

    private suspend fun sendTeam(nodes: NodeClient, messages: MessageClient, update: TeamUpdate) {
        try {
            val bytes = encodeTeamUpdate(update).toByteArray(Charsets.UTF_8)
            nodes.connectedNodes.await().forEach { messages.sendMessage(it.id, TAK_TEAM_PATH, bytes).await() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "team update failed", error)
        }
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            TakLinkNotification.NOTIFICATION_ID,
            TakLinkNotification.build(this, takStatusText(TakStore.state.value, System.currentTimeMillis())),
        )
    }

    private fun shutdown() {
        listener?.let { Wearable.getMessageClient(this).removeListener(it) }
        listener = null
        location?.stop()
        location = null
        link = null
        scope?.cancel()
        scope = null
        publish = PublishState()
        roster = TeamRoster()
        fix = null
        fixAtMs = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A failed start stops the service right away; its reason must outlive the shutdown so the tab can show it.
        TakStore.update { TakUiState(error = it.error) }
    }
}

private data class TeamSnapshot(val update: TeamUpdate, val count: Int, val fixAtMs: Long?)

private object TakLinkNotification {
    const val NOTIFICATION_ID = 12
    private const val CHANNEL_ID = "blindside_tak"
    private const val REQUEST_STOP = 3

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Enlace TAK", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, status: String): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_radar)
        .setContentTitle("Enlace TAK")
        .setContentText(status)
        .setOngoing(true)
        .setContentIntent(openAppIntent(context))
        .addAction(R.drawable.ic_radar, "Detener", stopIntent(context))
        .build()

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP,
        Intent(context, TakLinkService::class.java).setAction(TakLinkService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
