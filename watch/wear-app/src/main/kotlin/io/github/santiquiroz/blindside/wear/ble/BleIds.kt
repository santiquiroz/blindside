package io.github.santiquiroz.blindside.wear.ble

import java.util.UUID

val SERVICE_UUID: UUID = UUID.fromString("569f3867-024f-4498-a979-90a762ad3593")
val STREAM_UUID: UUID = UUID.fromString("37869398-ecc2-4915-90a1-13d39d708ad5")
val INFO_UUID: UUID = UUID.fromString("278b9369-d8ac-4eda-868b-7bfd0dea5dc6")
val CONTROL_UUID: UUID = UUID.fromString("725c9a6e-0c7b-45d2-bef6-48c03be7c092")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val REQUIRED_CHARACTERISTICS: List<UUID> = listOf(STREAM_UUID, INFO_UUID, CONTROL_UUID)

const val REQUESTED_MTU = 517
const val BLINDSIDE_NAME_PREFIX = "Blindside-"

private const val CMD_SESSION_ACTIVE: Byte = 0x04

fun sessionActiveCommand(active: Boolean): ByteArray = byteArrayOf(CMD_SESSION_ACTIVE, if (active) 1 else 0)

fun isBlindsideName(name: String?): Boolean = name?.startsWith(BLINDSIDE_NAME_PREFIX) == true
