package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.core.scene.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarLabelsTest {
    private fun scene(linkUp: Boolean = true, eliminated: Boolean = false, imuBAlive: Boolean = true) = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = linkUp,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, imuBAlive)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = eliminated,
    )

    @Test
    fun `no data shows dashes`() {
        assertEquals(NO_DATA_LABEL, centerLabel(null, ambient = false))
        assertEquals(NO_DATA_LABEL, centerLabel(scene(linkUp = false), ambient = false))
        assertEquals(NO_DATA_LABEL, centerLabel(scene(), ambient = true))
    }

    @Test
    fun `eliminated wins over everything else`() {
        assertEquals(ELIMINATED_LABEL, centerLabel(scene(linkUp = false, eliminated = true), ambient = false))
    }

    @Test
    fun `a live scene has no centre label`() {
        assertNull(centerLabel(scene(), ambient = false))
    }

    @Test
    fun `the link message replaces the dashes while connecting`() {
        assertEquals(CONNECTING_TO_BELT_LABEL, centerLabel(null, ambient = false, linkMessage = CONNECTING_TO_BELT_LABEL))
        assertEquals(CONNECTING_TO_BELT_LABEL, centerLabel(scene(linkUp = false), ambient = false, linkMessage = CONNECTING_TO_BELT_LABEL))
    }

    @Test
    fun `ambient and eliminated still win over the link message`() {
        assertEquals(NO_DATA_LABEL, centerLabel(null, ambient = true, linkMessage = CONNECTING_TO_BELT_LABEL))
        assertEquals(ELIMINATED_LABEL, centerLabel(scene(linkUp = false, eliminated = true), ambient = false, linkMessage = CONNECTING_TO_BELT_LABEL))
    }

    @Test
    fun `the do not disturb warning fits one short line`() {
        assertTrue(DND_RADAR_WARNING.length <= 30)
    }

    @Test
    fun `the most serious warning is shown`() {
        assertEquals("RADAR CAÍDO", warningLabel(setOf(Warning.YAW_UNCALIBRATED, Warning.RADAR_DOWN)))
        assertEquals("RUMBO SIN CALIBRAR", warningLabel(setOf(Warning.YAW_UNCALIBRATED)))
        assertNull(warningLabel(emptySet()))
    }

    @Test
    fun `link lost alone is left to the dashes`() {
        assertNull(warningLabel(setOf(Warning.LINK_LOST)))
    }

    @Test
    fun `every warning has a label`() {
        assertTrue(Warning.entries.all { labelFor(it).isNotBlank() })
    }

    @Test
    fun `the mini status lists link, radars and imus`() {
        val items = statusItems(scene(imuBAlive = false), watchSteps = true)
        assertEquals(listOf("BLE", "R-A", "R-B", "I-A", "I-B"), items.map { it.label })
        assertEquals(listOf(true, true, true, true, false), items.map { it.ok })
    }

    @Test
    fun `missing watch steps are flagged`() {
        assertEquals(StatusItem(NO_WATCH_STEPS_LABEL, ok = false), statusItems(scene(), watchSteps = false).last())
    }

    @Test
    fun `without a scene nothing is ok`() {
        assertTrue(statusItems(null, watchSteps = true).none { it.ok })
    }

    @Test
    fun `the eliminated button names its action`() {
        assertEquals("ME DIERON", eliminatedActionLabel(eliminated = false))
        assertEquals("REAPARECÍ", eliminatedActionLabel(eliminated = true))
    }

    @Test
    fun `status chips mark ok with a filled dot and faults with a hollow one`() {
        assertEquals(StatusMark.FILLED, statusMark(StatusItem("BLE", ok = true)))
        assertEquals(StatusMark.HOLLOW, statusMark(StatusItem("BLE", ok = false)))
    }

    @Test
    fun `long status labels move to their own row`() {
        val rows = statusRows(statusItems(scene(), watchSteps = false))
        assertEquals(listOf(listOf("BLE", "R-A", "R-B", "I-A", "I-B"), listOf(NO_WATCH_STEPS_LABEL)), rows.map { row -> row.map { it.label } })
        assertEquals(1, statusRows(statusItems(scene(), watchSteps = true)).size)
    }
}
