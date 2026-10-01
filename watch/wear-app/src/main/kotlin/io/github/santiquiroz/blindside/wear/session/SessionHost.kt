package io.github.santiquiroz.blindside.wear.session

import android.app.Notification
import android.content.Context

interface SessionHost {
    val appVersion: String
    val notificationId: Int

    fun ensureNotificationChannel(context: Context)

    fun notification(context: Context, status: String): Notification
}
