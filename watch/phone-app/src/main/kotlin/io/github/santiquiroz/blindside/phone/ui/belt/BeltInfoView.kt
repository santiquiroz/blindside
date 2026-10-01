package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.phone.ui.common.formatDecimal
import io.github.santiquiroz.blindside.phone.ui.common.formatUptime
import io.github.santiquiroz.blindside.shared.ble.MIN_STREAM_MTU

data class RadarInfo(val id: Int, val firmware: String, val baud: Int)

data class ImuInfo(val id: Int, val whoAmI: Int, val repeats: Long)

data class ConnInfo(val role: String, val intervalMs: Double, val latency: Int, val timeoutMs: Int, val sent: Long, val dropped: Long)

data class BeltInfoView(
    val firmware: String?,
    val proto: Int?,
    val bootId: String?,
    val reset: String?,
    val mtu: Int?,
    val txPowerDbm: Int?,
    val uptimeS: Long?,
    val radars: List<RadarInfo>,
    val imus: List<ImuInfo>,
    val conns: List<ConnInfo>,
    val legacyConn: ConnInfo?,
    val bonds: Int?,
)

data class InfoRow(val label: String, val value: String, val ok: Boolean = true)

private const val WATCH_ROLE = "watch"
private const val PHONE_ROLE = "phone"

fun parseBeltInfoView(json: String): BeltInfoView? {
    val root = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    return BeltInfoView(
        firmware = root["fw"] as? String,
        proto = intOf(root["proto"]),
        bootId = root["boot_id"] as? String,
        reset = root["reset"] as? String,
        mtu = intOf(root["mtu"]),
        txPowerDbm = intOf(root["tx_power_dbm"]),
        uptimeS = longOf(root["uptime_s"]),
        radars = objects(root["radars"]).mapNotNull(::radarInfoOf),
        imus = objects(root["imus"]).mapNotNull(::imuInfoOf),
        conns = objects(root["conns"]).map(::connInfoOf),
        legacyConn = (root["conn"] as? Map<*, *>)?.let(::legacyConnOf),
        bonds = intOf(root["bonds"]),
    )
}

fun infoRows(view: BeltInfoView): List<InfoRow> =
    headerRows(view) + view.radars.map(::radarRow) + view.imus.map(::imuRow) + linkRows(view) + connRows(view)

fun counterRows(counters: PipelineCounters?, rssiDbm: Int?): List<InfoRow> {
    if (counters == null) return emptyList()
    return listOfNotNull(
        InfoRow("Paquetes recibidos", "${counters.packets}"),
        InfoRow("Paquetes perdidos", "${counters.lostPackets}"),
        InfoRow("Paquetes malformados", "${counters.malformedPackets}", ok = counters.malformedPackets == 0L),
        InfoRow("Paquetes truncados", "${counters.truncatedPackets}", ok = counters.truncatedPackets == 0L),
        InfoRow("Reinicios del cinturón", "${counters.espResets}"),
        rssiDbm?.let { InfoRow("Señal", "$it dBm") },
    )
}

fun roleLabel(role: String): String = when (role) {
    WATCH_ROLE -> "reloj"
    PHONE_ROLE -> "celular"
    else -> role
}

private fun headerRows(view: BeltInfoView): List<InfoRow> = listOfNotNull(
    InfoRow("Firmware", firmwareText(view)),
    view.reset?.let { InfoRow("Último arranque", listOfNotNull(it, view.bootId).joinToString(" · ")) },
    view.uptimeS?.let { InfoRow("Encendido", formatUptime(it)) },
)

private fun linkRows(view: BeltInfoView): List<InfoRow> = listOfNotNull(
    view.txPowerDbm?.let { InfoRow("Potencia", "$it dBm") },
    view.mtu?.let { InfoRow("MTU", "$it", ok = it >= MIN_STREAM_MTU) },
    view.bonds?.let { InfoRow("Dispositivos emparejados", "$it") },
)

private fun connRows(view: BeltInfoView): List<InfoRow> =
    if (view.conns.isNotEmpty()) view.conns.map(::connRow) else listOfNotNull(view.legacyConn?.let(::legacyConnRow))

private fun firmwareText(view: BeltInfoView): String =
    listOfNotNull(view.firmware, view.proto?.let { "proto $it" }).joinToString(" · ").ifEmpty { "desconocido" }

private fun radarRow(radar: RadarInfo): InfoRow {
    val detected = radar.baud > 0
    val value = if (detected) "${radar.firmware} · ${radar.baud} baud" else "no detectado"
    return InfoRow("Radar ${letterOf(radar.id)}", value, ok = detected)
}

private fun imuRow(imu: ImuInfo): InfoRow {
    val found = imu.whoAmI != 0
    val value = if (found) "WHO ${imu.whoAmI} · ${imu.repeats} repeticiones" else "no encontrado"
    return InfoRow("IMU ${letterOf(imu.id)}", value, ok = found)
}

// Spec §2: the belt drops phone packets to protect the watch, so only drops on the watch link are a fault.
private fun connRow(conn: ConnInfo): InfoRow = InfoRow(
    "Conexión ${roleLabel(conn.role)}",
    "${formatDecimal(conn.intervalMs)} ms · enviados ${conn.sent} · descartados ${conn.dropped}",
    ok = !(conn.role == WATCH_ROLE && conn.dropped > 0),
)

private fun legacyConnRow(conn: ConnInfo): InfoRow =
    InfoRow("Conexión", "${formatDecimal(conn.intervalMs)} ms · latencia ${conn.latency} · supervisión ${conn.timeoutMs} ms")

private fun letterOf(id: Int): String = if (id == RADAR_A) "A" else "B"

private fun objects(value: Any?): List<Map<*, *>> = (value as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

private fun intOf(value: Any?): Int? = (value as? Double)?.toInt()

private fun longOf(value: Any?): Long? = (value as? Double)?.toLong()

private fun radarInfoOf(fields: Map<*, *>): RadarInfo? =
    intOf(fields["id"])?.let { RadarInfo(it, fields["fw"] as? String ?: "", intOf(fields["baud"]) ?: 0) }

private fun imuInfoOf(fields: Map<*, *>): ImuInfo? =
    intOf(fields["id"])?.let { ImuInfo(it, intOf(fields["who"]) ?: 0, longOf(fields["repeats"]) ?: 0L) }

private fun connInfoOf(fields: Map<*, *>): ConnInfo = ConnInfo(
    role = fields["role"] as? String ?: "?",
    intervalMs = fields["itvl_ms"] as? Double ?: 0.0,
    latency = intOf(fields["lat"]) ?: 0,
    timeoutMs = intOf(fields["timeout_ms"]) ?: 0,
    sent = longOf(fields["sent"]) ?: 0L,
    dropped = longOf(fields["dropped"]) ?: 0L,
)

private fun legacyConnOf(fields: Map<*, *>): ConnInfo = ConnInfo(
    role = "",
    intervalMs = fields["interval_ms"] as? Double ?: 0.0,
    latency = intOf(fields["latency"]) ?: 0,
    timeoutMs = intOf(fields["timeout_ms"]) ?: 0,
    sent = 0L,
    dropped = 0L,
)
