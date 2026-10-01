package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltCommand

data class CommandExtras(val kind: String, val radarId: Int)

const val COMMAND_RESTART_RADAR = "restart_radar"
const val COMMAND_IDENTIFY = "identify"
const val NO_RADAR_ID = -1

// The role belongs to the link setup and the pairing window has its own action, so neither may arrive as a manual command.
private const val COMMAND_NOT_MANUAL = ""

private val VALID_RADAR_IDS = 0..1

fun commandExtras(command: BeltCommand): CommandExtras = when (command) {
    is BeltCommand.RestartRadar -> CommandExtras(COMMAND_RESTART_RADAR, command.radarId)
    BeltCommand.Identify -> CommandExtras(COMMAND_IDENTIFY, NO_RADAR_ID)
    is BeltCommand.SetRole, BeltCommand.OpenPairingWindow -> CommandExtras(COMMAND_NOT_MANUAL, NO_RADAR_ID)
}

// The service receives intents, so anything that is not a known command for radar A or B is dropped here.
fun commandFrom(extras: CommandExtras): BeltCommand? = when (extras.kind) {
    COMMAND_RESTART_RADAR -> extras.radarId.takeIf { it in VALID_RADAR_IDS }?.let { BeltCommand.RestartRadar(it) }
    COMMAND_IDENTIFY -> BeltCommand.Identify
    else -> null
}
