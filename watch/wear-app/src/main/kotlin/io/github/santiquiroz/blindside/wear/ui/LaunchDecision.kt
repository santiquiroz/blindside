package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.permissions.shouldRequestPermissions
import io.github.santiquiroz.blindside.wear.settings.AppSettings

enum class LaunchAction { SHOW_HOME, SHOW_RADAR, START_RADAR }

enum class StartTapAction { REQUEST_PERMISSIONS, START_SESSION }

fun launchAction(settings: AppSettings, sessionRunning: Boolean, permissionsGranted: Boolean): LaunchAction = when {
    !settings.autoStartRadar -> LaunchAction.SHOW_HOME
    sessionRunning -> LaunchAction.SHOW_RADAR
    shouldAutoStart(settings, permissionsGranted) -> LaunchAction.START_RADAR
    else -> LaunchAction.SHOW_HOME
}

// The first pairing needs the user on the home screen, and a missing Bluetooth grant needs a prompt they must see.
fun shouldAutoStart(settings: AppSettings, permissionsGranted: Boolean): Boolean =
    settings.autoStartRadar && settings.beltAddress != null && permissionsGranted

fun startTapAction(grants: Map<String, Boolean>): StartTapAction =
    if (shouldRequestPermissions(grants)) StartTapAction.REQUEST_PERMISSIONS else StartTapAction.START_SESSION
