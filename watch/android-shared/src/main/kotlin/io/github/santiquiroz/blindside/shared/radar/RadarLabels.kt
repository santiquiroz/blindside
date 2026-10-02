package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.core.scene.Warning

data class StatusItem(val label: String, val ok: Boolean)

const val NO_DATA_LABEL = "--"
const val ELIMINATED_LABEL = "ELIMINADO"
const val NO_WATCH_STEPS_LABEL = "SIN PASOS"
const val CONNECTING_TO_BELT_LABEL = "Conectando con el cinturón…"
const val DND_RADAR_WARNING = "NO MOLESTAR: PUEDE NO VIBRAR"

private val WARNING_PRIORITY = listOf(
    Warning.RADAR_DOWN,
    Warning.IMU_DOWN,
    Warning.NO_IMU_COMPENSATION,
    Warning.PRONE,
    Warning.ALERT_OVERFLOW,
    Warning.CORRUPT_FRAMES,
    Warning.YAW_UNCALIBRATED,
)

fun centerLabel(scene: RadarScene?, ambient: Boolean, linkMessage: String? = null): String? = when {
    scene?.eliminated == true -> ELIMINATED_LABEL
    ambient -> NO_DATA_LABEL
    linkMessage != null -> linkMessage
    scene == null || !scene.linkUp -> NO_DATA_LABEL
    else -> null
}

fun warningLabel(warnings: Set<Warning>): String? = WARNING_PRIORITY.firstOrNull { it in warnings }?.let(::labelFor)

fun labelFor(warning: Warning): String = when (warning) {
    Warning.LINK_LOST -> "SIN ENLACE"
    Warning.RADAR_DOWN -> "RADAR CAÍDO"
    Warning.IMU_DOWN -> "IMU CAÍDO"
    Warning.NO_IMU_COMPENSATION -> "MÁS FANTASMAS AL MOVERTE"
    Warning.PRONE -> "RADAR DEGRADADO"
    Warning.CORRUPT_FRAMES -> "TRAMAS CORRUPTAS"
    Warning.ALERT_OVERFLOW -> "SATURADO"
    Warning.YAW_UNCALIBRATED -> "RUMBO SIN CALIBRAR"
}

fun statusItems(scene: RadarScene?, watchSteps: Boolean): List<StatusItem> = listOfNotNull(
    StatusItem("BLE", scene?.linkUp == true),
    StatusItem("R-A", isAlive(scene?.radars, RADAR_A)),
    StatusItem("R-B", isAlive(scene?.radars, RADAR_B)),
    StatusItem("I-A", isAlive(scene?.imus, RADAR_A)),
    StatusItem("I-B", isAlive(scene?.imus, RADAR_B)),
    if (watchSteps) null else StatusItem(NO_WATCH_STEPS_LABEL, ok = false),
)

private fun isAlive(sensors: List<SensorStatus>?, id: Int): Boolean = sensors?.firstOrNull { it.id == id }?.alive == true

enum class StatusMark { FILLED, HOLLOW }

private const val SHORT_STATUS_LABEL = 3

// Colour is never the only signal (spec §6), so each chip also carries a filled or hollow dot.
fun statusMark(item: StatusItem): StatusMark = if (item.ok) StatusMark.FILLED else StatusMark.HOLLOW

// The round screen narrows at the bottom: long labels such as "SIN PASOS" go on their own row.
fun statusRows(items: List<StatusItem>): List<List<StatusItem>> =
    items.partition { it.label.length <= SHORT_STATUS_LABEL }.toList().filter { it.isNotEmpty() }
