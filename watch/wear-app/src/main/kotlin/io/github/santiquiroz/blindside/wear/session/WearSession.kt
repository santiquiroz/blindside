package io.github.santiquiroz.blindside.wear.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.session.SessionCommands
import io.github.santiquiroz.blindside.shared.session.SessionHost
import io.github.santiquiroz.blindside.wear.BuildConfig

val WearSessionCommands = SessionCommands(BlindsideSessionService::class.java)

object WearSessionHost : SessionHost {
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val notificationId: Int = SessionNotification.NOTIFICATION_ID
    override val beltProfile: BeltLinkProfile = BeltLinkProfile(BeltRole.WATCH, activatesSession = true)

    override fun ensureNotificationChannel(context: Context) = SessionNotification.ensureChannel(context)

    override fun notification(context: Context, status: String): Notification = SessionNotification.build(context, status)
}
