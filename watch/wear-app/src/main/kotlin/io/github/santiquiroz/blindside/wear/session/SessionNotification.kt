package io.github.santiquiroz.blindside.wear.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.github.santiquiroz.blindside.wear.MainActivity
import io.github.santiquiroz.blindside.wear.R

object SessionNotification {
    const val NOTIFICATION_ID = 7
    private const val CHANNEL_ID = "blindside_session"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Partida", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, status: String): Notification {
        val openApp = openAppIntent(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle("Blindside")
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_radar, "Eliminado", toggleEliminatedIntent(context))
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_radar)
            .setTouchIntent(openApp)
            .setStatus(Status.Builder().addTemplate(status).build())
            .build()
            .apply(context)
        return builder.build()
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun toggleEliminatedIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        1,
        WearSessionCommands.toggleEliminatedIntent(context),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
