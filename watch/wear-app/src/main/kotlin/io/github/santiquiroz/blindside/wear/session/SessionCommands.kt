package io.github.santiquiroz.blindside.wear.session

import android.app.Service
import android.content.Context
import android.content.Intent

class SessionCommands(private val serviceClass: Class<out Service>) {
    fun start(context: Context, source: SessionSource, wakeLock: Boolean = true) {
        val intent = serviceIntent(context, SessionActions.ACTION_START)
            .putExtra(SessionActions.EXTRA_SOURCE, source.name)
            .putExtra(SessionActions.EXTRA_WAKE_LOCK, wakeLock)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_STOP))
    }

    fun marker(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_MARKER))
    }

    fun retryLink(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_RETRY_LINK))
    }

    fun toggleEliminatedIntent(context: Context): Intent = serviceIntent(context, SessionActions.ACTION_TOGGLE_ELIMINATED)

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, serviceClass).setAction(action)
}
