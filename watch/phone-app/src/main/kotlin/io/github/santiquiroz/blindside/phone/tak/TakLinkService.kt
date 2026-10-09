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
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
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
import io.github.santiquiroz.blindside.shared.tactical.distanceM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

private const val TAG = "TakLinkService"
private const val TEAM_FIX_FRESH_MS = 15_000L
private const val PICTURE_PERIOD_MS = 1_000L
private const val SELF_PUBLISH_PERIOD_MS = 5_000L
private const val ALERT_BASE_ID = 1000
private const val TEAM_RESEND_MS = 5_000L
private const val TEAM_MIN_MOVE_M = 1.0
private const val NODE_CACHE_MS = 30_000L

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
    private var beacon: TeamBeaconAdvertiser? = null
    private var listener: MessageClient.OnMessageReceivedListener? = null
    private val mutex = Mutex()
    private var publish = PublishState()
    private var roster = TeamRoster()
    private var board = ContactBoard()
    private var alertBook = AlertBook()
    private var fix: GeoFix? = null
    private var fixAtMs: Long? = null
    private var prefsState: StateFlow<TakPrefs>? = null
    private var lastTeamSentAtMs: Long? = null
    private var lastTeamSentFix: GeoFix? = null
    private var lastStatusText: String? = null

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
        val status = takStatusText(TakStore.state.value, System.currentTimeMillis())
        startForeground(
            TakLinkNotification.NOTIFICATION_ID,
            TakLinkNotification.build(this, status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        lastStatusText = status
        true
    } catch (error: SecurityException) {
        TakStore.update { it.copy(running = false, error = "Falta permiso de ubicación") }
        stopSelf()
        false
    }

    private suspend fun boot() {
        val repository = applicationContext.takPrefsRepository()
        val prefs = repository.current()
        val current = scope ?: return
        prefsState = repository.prefs.stateIn(current, SharingStarted.Eagerly, prefs)
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
        current.launch { takLink.run() }
        current.launch { mirrorStatus(takLink) }
        current.launch { collectIncoming(takLink, ids) }
        startGps()
        addTelemetryListener(ids)
        startBeacon(ids)
        current.launch { teamLoop(ids) }
        current.launch { pictureLoop(ids) }
    }

    private fun startGps() {
        val gps = TakLocation(this) { fresh, atMs ->
            scope?.launch { mutex.withLock { fix = fresh; fixAtMs = atMs } }
        }
        location = gps
        if (!gps.start()) TakStore.update { it.copy(error = "Falta permiso de ubicación") }
    }

    private fun startBeacon(ids: TakIds) {
        val beacons = TeamBeaconAdvertiser(this)
        beacon = beacons
        beacons.start(beaconIdOf(ids.deviceId))
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
        val publishContacts = prefsState?.value?.publishContacts ?: return
        val now = System.currentTimeMillis()
        val sent = mutex.withLock {
            val outgoing = publishTelemetry(publish, telemetry, fix, fixAtMs?.let { now - it }, ids, publishContacts, now)
            publish = outgoing.state
            outgoing.events.forEach { link?.send(it) }
            outgoing.contactsSent
        }
        if (sent > 0) TakStore.update { it.copy(contactsSent = it.contactsSent + sent) }
    }

    private suspend fun collectIncoming(takLink: TakLink, ids: TakIds) {
        val ownUidPrefix = "BLINDSIDE-${ids.deviceId}-"
        takLink.incoming.collect { event ->
            val now = System.currentTimeMillis()
            mutex.withLock {
                roster = roster.with(event, ids.callsign, now)
                board = board.with(event, ownUidPrefix, now)
            }
        }
    }

    private suspend fun mirrorStatus(takLink: TakLink) {
        takLink.status.collect { status -> TakStore.update { it.copy(link = status) } }
    }

    private suspend fun teamLoop(ids: TakIds) {
        val nodes = NodeIdCache { Wearable.getNodeClient(this).connectedNodes.await().map { it.id } }
        val messages = Wearable.getMessageClient(this)
        while (true) {
            delay(TAK_TEAM_PERIOD_MS)
            val now = System.currentTimeMillis()
            val team = mutex.withLock { teamSnapshot(now, ids) }
            if (teamSendDue(team.count, movedSinceLastTeamSend(team.update.self), lastTeamSentAtMs?.let { now - it })) {
                lastTeamSentAtMs = now
                lastTeamSentFix = team.update.self
                sendTeam(nodes, messages, team.update, now)
            }
            TakStore.update { it.copy(mates = team.count, lastFixAtMs = team.fixAtMs) }
            refreshNotification()
        }
    }

    private fun movedSinceLastTeamSend(fix: GeoFix?): Double? {
        val last = lastTeamSentFix
        return if (fix != null && last != null) distanceM(last.point, fix.point) else null
    }

    private fun teamSnapshot(now: Long, ids: TakIds): TeamSnapshot {
        val fresh = freshFix(now)
        val mates = roster.mates(now)
        return TeamSnapshot(TeamUpdate(fresh, mates, me = beaconIdOf(ids.deviceId)), mates.size, fixAtMs)
    }

    private fun freshFix(now: Long): GeoFix? =
        if (fix != null && fixAtMs != null && now - fixAtMs!! <= TEAM_FIX_FRESH_MS) fix else null

    private suspend fun pictureLoop(ids: TakIds) {
        var lastPublishAtMs = 0L
        while (true) {
            delay(PICTURE_PERIOD_MS)
            val now = System.currentTimeMillis()
            val prefs = prefsState?.value ?: continue
            val snapshot = mutex.withLock { pictureSnapshot(prefs, now) }
            if (TakStore.hasObservers()) TakStore.update { it.copy(picture = snapshot.picture) }
            snapshot.alerts.forEach { raiseAlert(it) }
            if (shouldPublishSelf(prefs, snapshot.picture.self, now, lastPublishAtMs)) {
                lastPublishAtMs = now
                val self = snapshot.picture.self!!
                link?.send(selfEvent("BLINDSIDE-${ids.deviceId}-SA", ids.callsign, self.point, self.accuracyM, now))
            }
        }
    }

    private fun pictureSnapshot(prefs: TakPrefs, now: Long): PictureSnapshot {
        val fresh = freshFix(now)
        val contacts = board.contacts(now)
        val alerts = if (prefs.proximityAlerts) {
            val round = dueAlerts(alertBook, contacts, fresh?.point, now)
            alertBook = round.book
            round.alerts
        } else {
            emptyList()
        }
        return PictureSnapshot(TeamPicture(fresh, roster.mates(now), contacts), alerts)
    }

    private fun shouldPublishSelf(prefs: TakPrefs, self: GeoFix?, now: Long, lastPublishAtMs: Long): Boolean =
        prefs.publishSelf && self != null && now - lastPublishAtMs >= SELF_PUBLISH_PERIOD_MS

    private fun raiseAlert(alert: ProximityAlert) {
        vibrateAlert()
        postAlert(alert)
    }

    private fun vibrateAlert() {
        getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
    }

    private fun postAlert(alert: ProximityAlert) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, TakLinkNotification.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blindside)
            .setContentTitle("Blindside")
            .setContentText(alertText(alert))
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_BASE_ID + (alert.uid.hashCode() and 0xFFF), notification)
    }

    private suspend fun sendTeam(nodes: NodeIdCache, messages: MessageClient, update: TeamUpdate, nowMs: Long) {
        try {
            val bytes = encodeTeamUpdate(update).toByteArray(Charsets.UTF_8)
            nodes.ids(nowMs).forEach { messages.sendMessage(it, TAK_TEAM_PATH, bytes).await() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            nodes.invalidate()
            Log.w(TAG, "team update failed", error)
        }
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val status = takStatusText(TakStore.state.value, System.currentTimeMillis())
        if (status == lastStatusText) return
        lastStatusText = status
        manager.notify(TakLinkNotification.NOTIFICATION_ID, TakLinkNotification.build(this, status))
    }

    private fun shutdown() {
        listener?.let { Wearable.getMessageClient(this).removeListener(it) }
        listener = null
        location?.stop()
        location = null
        beacon?.stop()
        beacon = null
        link = null
        scope?.cancel()
        scope = null
        publish = PublishState()
        roster = TeamRoster()
        board = ContactBoard()
        alertBook = AlertBook()
        fix = null
        fixAtMs = null
        prefsState = null
        lastTeamSentAtMs = null
        lastTeamSentFix = null
        lastStatusText = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A failed start stops the service right away; its reason must outlive the shutdown so the tab can show it.
        TakStore.update { TakUiState(error = it.error) }
    }
}

fun teamSendDue(mates: Int, fixMovedM: Double?, sinceLastSendMs: Long?): Boolean =
    sinceLastSendMs == null || mates > 0 || fixMovedM == null || fixMovedM >= TEAM_MIN_MOVE_M || sinceLastSendMs >= TEAM_RESEND_MS

private class NodeIdCache(private val fetch: suspend () -> List<String>) {
    private var ids: List<String> = emptyList()
    private var atMs: Long? = null

    suspend fun ids(nowMs: Long): List<String> {
        val cachedAt = atMs
        if (cachedAt == null || nowMs - cachedAt >= NODE_CACHE_MS) {
            ids = fetch()
            atMs = nowMs
        }
        return ids
    }

    fun invalidate() {
        atMs = null
    }
}

private data class TeamSnapshot(val update: TeamUpdate, val count: Int, val fixAtMs: Long?)

private data class PictureSnapshot(val picture: TeamPicture, val alerts: List<ProximityAlert>)

private object TakLinkNotification {
    const val NOTIFICATION_ID = 12
    const val ALERT_CHANNEL_ID = "blindside_tak_alerts"
    private const val CHANNEL_ID = "blindside_tak"
    private const val REQUEST_STOP = 3

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Enlace TAK", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Avisos del equipo", NotificationManager.IMPORTANCE_HIGH))
    }

    fun build(context: Context, status: String): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_blindside)
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
