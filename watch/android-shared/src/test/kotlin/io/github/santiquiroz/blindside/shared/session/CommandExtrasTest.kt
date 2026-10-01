package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandExtrasTest {
    @Test
    fun `restart and identify survive the trip through intent extras`() {
        listOf(BeltCommand.RestartRadar(0), BeltCommand.RestartRadar(1), BeltCommand.Identify).forEach { command ->
            assertEquals(command, commandFrom(commandExtras(command)))
        }
    }

    @Test
    fun `a radar id other than A or B is rejected`() {
        assertNull(commandFrom(CommandExtras(COMMAND_RESTART_RADAR, 2)))
        assertNull(commandFrom(CommandExtras(COMMAND_RESTART_RADAR, NO_RADAR_ID)))
    }

    @Test
    fun `unknown command kinds are rejected`() {
        assertNull(commandFrom(CommandExtras("format", 0)))
    }

    @Test
    fun `the role and the pairing window never travel as manual commands`() {
        assertNull(commandFrom(commandExtras(BeltCommand.SetRole(BeltRole.PHONE))))
        assertNull(commandFrom(commandExtras(BeltCommand.OpenPairingWindow)))
    }
}
