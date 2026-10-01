package io.github.santiquiroz.blindside.shared.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface SessionHost {
    val appVersion: String
    val notificationId: Int
    val beltProfile: BeltLinkProfile

    fun ensureNotificationChannel(context: Context)

    fun notification(context: Context, status: String): Notification

    fun launchCompanions(context: Context, scope: CoroutineScope): List<Job> = emptyList()

    suspend fun traitsFor(context: Context, purpose: SessionPurpose): SessionTraits = SessionTraits(beltProfile)

    fun foregroundTypes(source: SessionSource): Int = foregroundTypesFor(source)

    fun beltListener(inner: BeltListener): BeltListener = inner

    fun onScene(counters: PipelineCounters) = Unit
}
