package io.github.santiquiroz.blindside.shared.ble

enum class BeltRole(val code: Int) { WATCH(0), PHONE(1) }

sealed interface BeltCommand {
    data class RestartRadar(val radarId: Int) : BeltCommand
    data object Identify : BeltCommand
    data class SetRole(val role: BeltRole) : BeltCommand
    data object OpenPairingWindow : BeltCommand
}

enum class LinkPriority { HIGH, BALANCED }

data class BeltLinkProfile(val role: BeltRole = BeltRole.WATCH, val activatesSession: Boolean = true)

private const val CMD_RESTART_RADAR: Byte = 0x01
private const val CMD_IDENTIFY: Byte = 0x03
private const val CMD_SET_ROLE: Byte = 0x06
private const val CMD_OPEN_PAIRING_WINDOW: Byte = 0x05

fun commandBytes(command: BeltCommand): ByteArray = when (command) {
    is BeltCommand.RestartRadar -> byteArrayOf(CMD_RESTART_RADAR, command.radarId.toByte())
    BeltCommand.Identify -> byteArrayOf(CMD_IDENTIFY)
    is BeltCommand.SetRole -> byteArrayOf(CMD_SET_ROLE, command.role.code.toByte())
    BeltCommand.OpenPairingWindow -> byteArrayOf(CMD_OPEN_PAIRING_WINDOW)
}

fun connectPriorityFor(role: BeltRole): LinkPriority =
    if (role == BeltRole.WATCH) LinkPriority.HIGH else LinkPriority.BALANCED

// Spec §2: after 06 01 the belt asks for 60-100 ms itself, and any later request from the phone would override it.
fun settledPriorityFor(role: BeltRole): LinkPriority? =
    if (role == BeltRole.WATCH) LinkPriority.BALANCED else null
