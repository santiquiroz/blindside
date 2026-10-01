package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.shared.ble.BeltListener

class DiagnosticBeltListener(private val inner: BeltListener) : BeltListener by inner {
    override fun onBeltInfo(json: String, nowNanos: Long) {
        PhoneStore.update { it.copy(infoJson = json) }
        inner.onBeltInfo(json, nowNanos)
    }

    override fun onRssi(dbm: Int, nowNanos: Long) {
        PhoneStore.update { it.copy(rssiDbm = dbm) }
        inner.onRssi(dbm, nowNanos)
    }
}
