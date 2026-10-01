package io.github.santiquiroz.blindside.shared.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface SessionHost {
    val appVersion: String
    val notificationId: Int
    val beltProfile: BeltLinkProfile

    fun ensureNotificationChannel(context: Context)

    fun notification(context: Context, status: String): Notification

    fun launchCompanions(context: Context, scope: CoroutineScope): List<Job> = emptyList()
}
