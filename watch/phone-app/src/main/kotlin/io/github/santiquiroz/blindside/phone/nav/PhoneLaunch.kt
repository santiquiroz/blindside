package io.github.santiquiroz.blindside.phone.nav

import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_POST_NOTIFICATIONS
import io.github.santiquiroz.blindside.shared.settings.AppSettings

enum class PhoneLaunch { AUTO_START, NONE }

// Deviation P4: the phone reads no sensors, so it never asks for physical activity.
val PHONE_PERMISSIONS: Array<String> = arrayOf(PERMISSION_BLUETOOTH_SCAN, PERMISSION_BLUETOOTH_CONNECT, PERMISSION_POST_NOTIFICATIONS)

fun phoneLaunch(settings: AppSettings, sessionRunning: Boolean, bluetoothGranted: Boolean, linkSupport: LinkSupport): PhoneLaunch = when {
    sessionRunning || !bluetoothGranted -> PhoneLaunch.NONE
    wantsAutoStart(settings, linkSupport) -> PhoneLaunch.AUTO_START
    else -> PhoneLaunch.NONE
}

// The first pairing needs the user on screen, and on firmware 0.1.0 a phone radar would take the watch's only slot (Deviation P5).
private fun wantsAutoStart(settings: AppSettings, linkSupport: LinkSupport): Boolean =
    settings.autoStartRadar && settings.beltAddress != null && linkSupport == LinkSupport.DUAL_LINK
