package io.github.santiquiroz.blindside.wear.haptics

import android.app.NotificationManager
import android.content.Context
import io.github.santiquiroz.blindside.shared.settings.VibrationUsage

fun currentInterruptionFilter(context: Context): InterruptionFilter =
    interruptionFilterFrom(context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)

fun dndMaySilenceNow(context: Context, usage: VibrationUsage): Boolean = dndMaySilence(currentInterruptionFilter(context), usage)
