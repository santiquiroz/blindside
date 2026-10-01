package io.github.santiquiroz.blindside.phone.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.phone.BuildConfig
import io.github.santiquiroz.blindside.phone.settings.phonePrefsRepository
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltListener
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.session.SessionCommands
import io.github.santiquiroz.blindside.shared.session.SessionHost
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionService
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionTraits

object PhoneSessionHost : SessionHost {
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val notificationId: Int = PhoneNotification.NOTIFICATION_ID
    override val beltProfile: BeltLinkProfile = BeltLinkProfile(BeltRole.PHONE, activatesSession = true)

    override fun ensureNotificationChannel(context: Context) = PhoneNotification.ensureChannel(context)

    override fun notification(context: Context, status: String): Notification = PhoneNotification.build(context, status)

    override suspend fun traitsFor(context: Context, purpose: SessionPurpose): SessionTraits =
        phoneTraits(purpose, context.phonePrefsRepository().current().vibrate)

    override fun foregroundTypes(source: SessionSource): Int = PHONE_FOREGROUND_TYPES

    override fun beltListener(inner: BeltListener): BeltListener = DiagnosticBeltListener(inner)

    override fun onScene(counters: PipelineCounters) {
        PhoneStore.update { it.copy(counters = counters) }
    }
}

class PhoneSessionService : SessionService() {
    override val host: SessionHost = PhoneSessionHost
}

val PhoneSessionCommands = SessionCommands(PhoneSessionService::class.java)

// The phone holds no wake lock: it shows the radar, it does not buzz the player in the dark like the watch.
fun startPhoneSession(context: Context, purpose: SessionPurpose) =
    PhoneSessionCommands.start(context, SessionSource.BELT, wakeLock = false, purpose = purpose)
