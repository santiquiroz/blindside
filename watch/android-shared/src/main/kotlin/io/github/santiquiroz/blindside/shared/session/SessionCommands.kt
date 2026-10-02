package io.github.santiquiroz.blindside.shared.session

import android.app.Service
import android.content.Context
import android.content.Intent
import io.github.santiquiroz.blindside.shared.ble.BeltCommand

class SessionCommands(private val serviceClass: Class<out Service>) {
    fun start(
        context: Context,
        source: SessionSource,
        wakeLock: Boolean = true,
        purpose: SessionPurpose = SessionPurpose.GAME,
    ) {
        val intent = serviceIntent(context, SessionActions.ACTION_START)
            .putExtra(SessionActions.EXTRA_SOURCE, source.name)
            .putExtra(SessionActions.EXTRA_WAKE_LOCK, wakeLock)
            .putExtra(SessionActions.EXTRA_PURPOSE, purpose.name)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(stopIntent(context))
    }

    fun marker(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_MARKER))
    }

    fun retryLink(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_RETRY_LINK))
    }

    fun openPairing(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_OPEN_PAIRING))
    }

    fun send(context: Context, command: BeltCommand) {
        val extras = commandExtras(command)
        val intent = serviceIntent(context, SessionActions.ACTION_SEND_COMMAND)
            .putExtra(SessionActions.EXTRA_COMMAND, extras.kind)
            .putExtra(SessionActions.EXTRA_RADAR_ID, extras.radarId)
        context.startService(intent)
    }

    fun refreshInfo(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_REFRESH_INFO))
    }

    fun stopIntent(context: Context): Intent = serviceIntent(context, SessionActions.ACTION_STOP)

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, serviceClass).setAction(action)
}
