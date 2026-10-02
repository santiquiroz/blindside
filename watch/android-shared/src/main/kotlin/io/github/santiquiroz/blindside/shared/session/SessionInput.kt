package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.shared.settings.ScreenMode

sealed interface SessionInput {
    class Packet(val bytes: ByteArray, val arrivalNanos: Long) : SessionInput
    data class Gravity(val x: Float, val y: Float, val z: Float, val eventNanos: Long) : SessionInput
    data class Gyro(val x: Float, val y: Float, val z: Float, val eventNanos: Long) : SessionInput
    data class Step(val eventNanos: Long) : SessionInput
    data class BeltInfo(val json: String, val nowNanos: Long) : SessionInput
    data class Rssi(val dbm: Int, val nowNanos: Long) : SessionInput
    data class Link(val connected: Boolean, val nowNanos: Long) : SessionInput
    data class ModeChanged(val screenMode: ScreenMode, val nowNanos: Long) : SessionInput
    data class Marker(val nowNanos: Long) : SessionInput
    data class Tick(val nowNanos: Long) : SessionInput
    data class PlayDeferred(val alert: ContactAlert, val atNanos: Long) : SessionInput
    data object Flush : SessionInput
}
