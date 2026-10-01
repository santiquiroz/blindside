package io.github.santiquiroz.blindside.wear.ble

interface BeltListener {
    fun onPacket(bytes: ByteArray, arrivalNanos: Long)
    fun onBeltInfo(json: String, nowNanos: Long)
    fun onRssi(dbm: Int, nowNanos: Long)
    fun onLinkChanged(connected: Boolean, nowNanos: Long)
    fun onStatus(status: BleStatus)
}
