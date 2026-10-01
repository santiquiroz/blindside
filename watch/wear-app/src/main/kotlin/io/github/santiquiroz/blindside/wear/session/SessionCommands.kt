package io.github.santiquiroz.blindside.wear.session

import android.content.Context
import android.content.Intent

object SessionCommands {
    const val ACTION_START = "io.github.santiquiroz.blindside.action.START"
    const val ACTION_STOP = "io.github.santiquiroz.blindside.action.STOP"
    const val ACTION_MARKER = "io.github.santiquiroz.blindside.action.MARKER"
    const val ACTION_RETRY_LINK = "io.github.santiquiroz.blindside.action.RETRY_LINK"
    const val ACTION_TOGGLE_ELIMINATED = "io.github.santiquiroz.blindside.action.TOGGLE_ELIMINATED"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_WAKE_LOCK = "wake_lock"

    fun start(context: Context, source: SessionSource, wakeLock: Boolean = true) {
        val intent = serviceIntent(context, ACTION_START)
            .putExtra(EXTRA_SOURCE, source.name)
            .putExtra(EXTRA_WAKE_LOCK, wakeLock)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(serviceIntent(context, ACTION_STOP))
    }

    fun marker(context: Context) {
        context.startService(serviceIntent(context, ACTION_MARKER))
    }

    fun retryLink(context: Context) {
        context.startService(serviceIntent(context, ACTION_RETRY_LINK))
    }

    fun toggleEliminatedIntent(context: Context): Intent = serviceIntent(context, ACTION_TOGGLE_ELIMINATED)

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, BlindsideSessionService::class.java).setAction(action)
}
