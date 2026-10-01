package io.github.santiquiroz.blindside.shared.ble

const val MIN_STREAM_MTU = 247

enum class MtuAction { OK, RETRY_ONCE, FAIL }

private val INFO_MTU = Regex("\"mtu\"\\s*:\\s*(\\d+)")

// Spec §4.2: the ESP32 refuses to notify below 247, so an unknown MTU is left to that rule instead of guessed.
fun mtuAction(mtu: Int?, retried: Boolean): MtuAction = when {
    mtu == null || mtu >= MIN_STREAM_MTU -> MtuAction.OK
    retried -> MtuAction.FAIL
    else -> MtuAction.RETRY_ONCE
}

fun effectiveMtu(callbackMtu: Int?, infoMtu: Int?): Int? = listOfNotNull(callbackMtu, infoMtu).minOrNull()

fun mtuFromInfo(json: String): Int? = INFO_MTU.find(json)?.groupValues?.get(1)?.toIntOrNull()
