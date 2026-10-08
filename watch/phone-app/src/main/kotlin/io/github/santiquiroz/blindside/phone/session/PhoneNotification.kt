package io.github.santiquiroz.blindside.phone.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.santiquiroz.blindside.phone.MainActivity
import io.github.santiquiroz.blindside.phone.R

object PhoneNotification {
    const val NOTIFICATION_ID = 11
    private const val CHANNEL_ID = "blindside_phone_session"
    private const val REQUEST_STOP = 2

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Radar en curso", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, status: String): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_blindside)
        .setContentTitle("Blindside")
        .setContentText(status)
        .setOngoing(true)
        .setContentIntent(openAppIntent(context))
        .addAction(R.drawable.ic_radar, "Detener", servicePendingIntent(context, REQUEST_STOP, PhoneSessionCommands.stopIntent(context)))
        .build()

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getService(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
}
