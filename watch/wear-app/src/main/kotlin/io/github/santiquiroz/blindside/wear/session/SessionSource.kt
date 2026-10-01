package io.github.santiquiroz.blindside.wear.session

import android.content.pm.ServiceInfo

enum class SessionSource { BELT, DEMO }

enum class StartError { BLUETOOTH_PERMISSION_MISSING, BLUETOOTH_UNAVAILABLE }

fun foregroundTypesFor(source: SessionSource): Int = when (source) {
    SessionSource.BELT -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    SessionSource.DEMO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
}

fun startBlocker(source: SessionSource, bluetoothGranted: Boolean, hasAdapter: Boolean): StartError? = when {
    source == SessionSource.DEMO -> null
    !bluetoothGranted -> StartError.BLUETOOTH_PERMISSION_MISSING
    !hasAdapter -> StartError.BLUETOOTH_UNAVAILABLE
    else -> null
}

fun sourceFrom(name: String?): SessionSource = SessionSource.entries.firstOrNull { it.name == name } ?: SessionSource.BELT

fun ongoingStatus(eliminated: Boolean, source: SessionSource): String = when {
    eliminated -> "Eliminado"
    source == SessionSource.DEMO -> "Demo en curso"
    else -> "Partida en curso"
}
