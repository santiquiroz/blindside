package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.enumOrDefault

data class WatchStatus(
    val sessionActive: Boolean,
    val ble: BleStatus,
    val linkUp: Boolean,
    val radars: List<SensorStatus>,
    val updatedMs: Long,
)

fun watchStatusOf(session: SessionUiState, nowMs: Long): WatchStatus = WatchStatus(
    sessionActive = session.running,
    ble = session.ble,
    linkUp = session.scene?.linkUp == true,
    radars = session.scene?.radars.orEmpty(),
    updatedMs = nowMs,
)

fun encodeWatchStatus(status: WatchStatus): String = MiniJson.obj(
    listOf(
        "session_active" to status.sessionActive.toString(),
        "link" to MiniJson.quote(status.ble.name),
        "link_up" to status.linkUp.toString(),
        "radars" to MiniJson.array(status.radars.map(::encodeSensor)),
        "updated_ms" to status.updatedMs.toString(),
    ),
)

// Display-only: a link state a newer watch adds reads as IDLE instead of hiding the whole status.
fun decodeWatchStatus(json: String): WatchStatus? {
    val fields = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    val updatedMs = longField(fields, "updated_ms") ?: return null
    return WatchStatus(
        sessionActive = fields["session_active"] == true,
        ble = enumOrDefault(fields["link"] as? String, BleStatus.IDLE),
        linkUp = fields["link_up"] == true,
        radars = (fields["radars"] as? List<*>).orEmpty().mapNotNull(::decodeSensor),
        updatedMs = updatedMs,
    )
}

private fun encodeSensor(sensor: SensorStatus): String =
    MiniJson.obj(listOf("id" to sensor.id.toString(), "alive" to sensor.alive.toString()))

private fun decodeSensor(item: Any?): SensorStatus? {
    val fields = item as? Map<*, *> ?: return null
    val id = longField(fields, "id")?.toInt() ?: return null
    return SensorStatus(id, fields["alive"] == true)
}
