package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.phone.ui.common.formatAgo
import io.github.santiquiroz.blindside.phone.ui.radar.UPDATE_FIRMWARE_TEXT
import io.github.santiquiroz.blindside.phone.ui.radar.phoneLinkLabel
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.isStatusFresh
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import kotlin.math.roundToInt

enum class ActionBlock { NOT_CONNECTED, SESSION_ACTIVE }

data class PairingGuidance(val text: String, val canAskWatch: Boolean)

const val PAIRING_STEPS = "1. Con el radar del reloj en marcha, toca Pedir al reloj que abra la ventana " +
    "(o en el reloj, Ajustes → Emparejar celular; o BOOT 3 s en el primer minuto tras encender el cinturón).\n" +
    "2. Toca Iniciar radar.\n" +
    "3. Cuando Android lo pida, escribe la clave de 6 dígitos de la etiqueta del cinturón.\n" +
    "Si el cinturón ya tiene conectados el reloj y otro celular, no queda espacio: desconecta uno."

const val NO_WATCH_LINK_TEXT = "En el reloj, inicia el radar primero (o mantén BOOT 3 s en el primer minuto tras encender el cinturón)."

const val FIRMWARE_CAUTION = "Requiere el firmware 0.2.0 del cinturón: con el 0.1.0, emparejar el celular desempareja el reloj."

const val APPLY_ON_START_TEXT = "Mano, ángulos y signos se comparten con el reloj y se aplican al iniciar el radar."

fun phoneLinkUp(session: SessionUiState): Boolean = session.running && session.ble == BleStatus.STREAMING

fun restartBlock(linkUp: Boolean): ActionBlock? = if (linkUp) null else ActionBlock.NOT_CONNECTED

// Spec §2: the belt ignores IDENTIFY while any link has its session active, so the button says why instead of failing silently.
fun identifyBlock(linkUp: Boolean, purpose: SessionPurpose?, watchSessionActive: Boolean): ActionBlock? = when {
    !linkUp -> ActionBlock.NOT_CONNECTED
    purpose == SessionPurpose.GAME || watchSessionActive -> ActionBlock.SESSION_ACTIVE
    else -> null
}

fun actionBlockText(block: ActionBlock): String = when (block) {
    ActionBlock.NOT_CONNECTED -> "Conecta el cinturón para usar las acciones."
    ActionBlock.SESSION_ACTIVE -> "Identificar no funciona con una partida activa en el reloj o en el celular."
}

// On 0.1.0 the belt keeps one bond: pairing the phone there unpairs the watch (Deviation P5).
fun pairingGuidance(linkSupport: LinkSupport): PairingGuidance = when (linkSupport) {
    LinkSupport.DUAL_LINK -> PairingGuidance(PAIRING_STEPS, canAskWatch = true)
    LinkSupport.SINGLE_LINK -> PairingGuidance(UPDATE_FIRMWARE_TEXT, canAskWatch = false)
    LinkSupport.UNKNOWN -> PairingGuidance("$PAIRING_STEPS\n$FIRMWARE_CAUTION", canAskWatch = true)
}

fun pairingRequestMessage(result: BridgeResult<OpenPairingReply>): String = when (result) {
    is BridgeResult.Ok -> pairingReplyMessage(result.value)
    BridgeResult.NoWatch -> "No hay un reloj conectado: mantén BOOT 3 s en el primer minuto tras encender el cinturón."
    is BridgeResult.Failed -> "El reloj no respondió (${result.reason}): usa el botón BOOT del cinturón."
}

fun liveDiagnostics(phone: PhoneDiagnostics, running: Boolean): PhoneDiagnostics =
    if (running) phone else phone.copy(rssiDbm = null, counters = null)

fun beltLinkText(session: SessionUiState): String =
    if (!session.running) "Celular sin conexión al cinturón" else "${purposeLabel(session.purpose)}: ${phoneLinkLabel(session.ble)}"

fun watchStatusLine(status: WatchStatus?, nowMs: Long): String = when {
    status == null -> "Reloj: sin datos (abre Blindside en el reloj para compartir su estado)"
    !isStatusFresh(status, nowMs) -> "Reloj: sin datos recientes (${formatAgo(nowMs - status.updatedMs)})"
    else -> "Reloj: ${sessionWord(status)} · ${linkWord(status.linkUp)} · ${radarsWord(status.radars)}"
}

fun handednessLabel(handedness: Handedness): String = when (handedness) {
    Handedness.RIGHT -> "Diestro"
    Handedness.LEFT -> "Zurdo"
    Handedness.SWITCHER -> "Cambia de hombro"
}

fun yawText(radarId: Int, yawDeg: Double): String = "Radar ${if (radarId == RADAR_A) "A" else "B"} ${yawDeg.roundToInt()}°"

// The watch only queues 05 (plan 05 D8): REQUESTED means asked, and the belt's own reply shows on the watch.
private fun pairingReplyMessage(reply: OpenPairingReply): String = when (reply) {
    OpenPairingReply.REQUESTED -> "Pedido enviado: el reloj abre la ventana del cinturón por 60 s. Ahora toca Iniciar radar."
    OpenPairingReply.NO_LINK -> NO_WATCH_LINK_TEXT
}

private fun purposeLabel(purpose: SessionPurpose?): String = if (purpose == SessionPurpose.DIAGNOSTIC) "Diagnóstico" else "Radar"

private fun sessionWord(status: WatchStatus): String = if (status.sessionActive) "partida activa" else "sin partida"

private fun linkWord(linkUp: Boolean): String = if (linkUp) "enlace bien" else "enlace caído"

private fun radarsWord(radars: List<SensorStatus>): String = "radar A ${aliveWord(radars, RADAR_A)}, B ${aliveWord(radars, RADAR_B)}"

private fun aliveWord(radars: List<SensorStatus>, id: Int): String = if (radars.any { it.id == id && it.alive }) "bien" else "caído"
