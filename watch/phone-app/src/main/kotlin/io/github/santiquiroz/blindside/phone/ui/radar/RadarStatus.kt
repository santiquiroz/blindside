package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.radar.statusItems
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError

data class SensorChip(val label: String, val ok: Boolean)

const val START_RADAR_LABEL = "Iniciar radar"
const val BLUETOOTH_DENIED_TEXT = "Sin permiso de Bluetooth el radar no arranca. Concédelo en los ajustes de la app."
const val UPDATE_FIRMWARE_TEXT = "Actualiza el firmware del cinturón para usar reloj y celular a la vez."

private val CHIP_LABELS = mapOf("BLE" to "Enlace", "R-A" to "Radar A", "R-B" to "Radar B", "I-A" to "IMU A", "I-B" to "IMU B")

fun isLiveRadar(session: SessionUiState): Boolean = session.running && session.purpose == SessionPurpose.GAME

fun sensorChips(scene: RadarScene?): List<SensorChip> =
    statusItems(scene, watchSteps = true).map { SensorChip(CHIP_LABELS[it.label] ?: it.label, it.ok) }

fun phoneLinkLabel(status: BleStatus): String = when (status) {
    BleStatus.IDLE -> "Sin conexión"
    BleStatus.BLUETOOTH_OFF -> "Bluetooth apagado"
    BleStatus.SEARCHING -> "Buscando el cinturón. Primera vez: Cinturón → Pedir al reloj que abra la ventana " +
        "(o en el reloj, Ajustes → Emparejar celular, o BOOT 3 s). Si ya están conectados el reloj y otro celular, no queda espacio."
    BleStatus.PAIRING -> "Emparejando: escribe la clave de 6 dígitos de la etiqueta"
    BleStatus.PAIRING_FAILED -> "Clave incorrecta o ventana cerrada"
    BleStatus.CONNECTING -> "Conectando…"
    BleStatus.STREAMING -> "Recibiendo datos"
    BleStatus.RECONNECTING -> "Reconectando…"
    BleStatus.BOND_LOST -> "El cinturón olvidó este celular: olvídalo en Ajustes → Bluetooth y vuelve a emparejar"
    BleStatus.MTU_TOO_LOW -> "MTU insuficiente"
}

fun startErrorText(error: StartError): String = when (error) {
    StartError.BLUETOOTH_PERMISSION_MISSING -> BLUETOOTH_DENIED_TEXT
    StartError.BLUETOOTH_UNAVAILABLE -> "Este celular no tiene Bluetooth disponible."
}

fun radarBanner(session: SessionUiState): String? = session.startError?.let(::startErrorText) ?: linkBanner(session)

fun recordingLine(session: SessionUiState): String? =
    if (session.recordingFailed) "La grabación falló; el radar sigue." else session.recordingName?.let { "Grabando: $it" }

// On firmware 0.1.0 the belt holds one link: a phone radar would take the slot the watch reconnects to.
fun idleRadarHint(beltPaired: Boolean, purpose: SessionPurpose?, linkSupport: LinkSupport): String = when {
    purpose == SessionPurpose.DIAGNOSTIC -> "Hay un diagnóstico en curso: iniciar el radar lo reemplaza."
    linkSupport == LinkSupport.SINGLE_LINK -> "$UPDATE_FIRMWARE_TEXT Con este firmware el celular le quita el cinturón al reloj."
    beltPaired -> "Cinturón emparejado. Con el arranque automático activo, el radar se inicia solo al abrir la app."
    else -> "Primera vez: Cinturón → Pedir al reloj que abra la ventana (o en el reloj, Ajustes → Emparejar celular, " +
        "o BOOT 3 s en el cinturón) y luego toca Iniciar radar."
}

fun radarDescription(scene: RadarScene?): String = when {
    scene == null || !scene.linkUp -> "Radar sin enlace"
    scene.eliminated -> "Radar en modo eliminado"
    else -> "Radar con ${scene.blips.size} contactos"
}

private fun linkBanner(session: SessionUiState): String? =
    if (session.scene?.linkUp == true) null else phoneLinkLabel(session.ble)
