package io.github.santiquiroz.blindside.wear.ble

enum class BleStatus {
    IDLE,
    BLUETOOTH_OFF,
    SEARCHING,
    PAIRING,
    PAIRING_FAILED,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    BOND_LOST,
    MTU_TOO_LOW,
}

enum class LinkEvent { STREAM_READY, DISCONNECTED, BLUETOOTH_OFF, HALTED }

data class LinkReporter(val up: Boolean = false)

data class LinkReport(val reporter: LinkReporter, val change: Boolean?)

private val HALTED_STATUSES = setOf(BleStatus.PAIRING_FAILED, BleStatus.BOND_LOST, BleStatus.MTU_TOO_LOW)

fun needsRetry(status: BleStatus): Boolean = status in HALTED_STATUSES

fun report(reporter: LinkReporter, event: LinkEvent): LinkReport {
    val nowUp = event == LinkEvent.STREAM_READY
    return LinkReport(LinkReporter(nowUp), linkTransition(reporter.up, nowUp))
}

fun linkTransition(previouslyUp: Boolean, nowUp: Boolean): Boolean? = if (previouslyUp == nowUp) null else nowUp
