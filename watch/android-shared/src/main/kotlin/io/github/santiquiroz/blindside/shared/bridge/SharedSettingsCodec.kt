package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.shared.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.shared.settings.RadarSettings
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import io.github.santiquiroz.blindside.shared.settings.YAW_LIMIT_DEG

private const val YAW_KEY = "yaw_deg"
private val SPEED_SIGNS = setOf(1, -1)

fun encodeSharedSettings(shared: SharedSettings): String = MiniJson.obj(
    listOf(
        "handedness" to MiniJson.quote(shared.handedness.name),
        "posture" to MiniJson.quote(shared.posture.name),
        "updated_ms" to shared.updatedMs.toString(),
        "radars" to MiniJson.array(shared.radars.map(::encodeRadar)),
    ),
)

// Anything short of a complete, known payload is refused whole: a guessed default would overwrite hand, mounts, signs or posture.
fun decodeSharedSettings(json: String): SharedSettings? {
    val fields = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    val handedness = enumNamed<Handedness>(fields["handedness"]) ?: return null
    val posture = enumNamed<WatchPosture>(fields["posture"]) ?: return null
    val updatedMs = longField(fields, "updated_ms") ?: return null
    val radars = decodeRadars(fields["radars"]) ?: return null
    return SharedSettings(handedness, radars, posture, updatedMs)
}

private fun encodeRadar(radar: RadarSettings): String = MiniJson.obj(
    listOf(
        "id" to radar.radarId.toString(),
        YAW_KEY to (radar.yawDegOverride?.toString() ?: "null"),
        "flip_x" to radar.flipX.toString(),
        "speed_sign" to radar.speedSign.toString(),
    ),
)

private fun decodeRadars(value: Any?): List<RadarSettings>? {
    val items = value as? List<*> ?: return null
    val radars = items.mapNotNull(::decodeRadar)
    if (radars.size != items.size) return null
    return radars.sortedBy { it.radarId }.takeIf(::coversEveryRadar)
}

private fun decodeRadar(item: Any?): RadarSettings? {
    val fields = (item as? Map<*, *>)?.takeIf(::hasValidYaw) ?: return null
    val id = wholeField(fields, "id")?.toInt() ?: return null
    val flipX = fields["flip_x"] as? Boolean ?: return null
    val speedSign = speedSignIn(fields) ?: return null
    return RadarSettings(id, clampedYaw(fields[YAW_KEY] as? Double), flipX, speedSign)
}

// The key must be there: null means "use the geometry's yaw", a missing key means a partial payload.
private fun hasValidYaw(fields: Map<*, *>): Boolean =
    fields.containsKey(YAW_KEY) && fields[YAW_KEY].let { it == null || it is Double }

private fun speedSignIn(fields: Map<*, *>): Int? = wholeField(fields, "speed_sign")?.toInt()?.takeIf { it in SPEED_SIGNS }

private fun clampedYaw(deg: Double?): Double? = deg?.coerceIn(-YAW_LIMIT_DEG, YAW_LIMIT_DEG)

private fun coversEveryRadar(radars: List<RadarSettings>): Boolean =
    radars.map { it.radarId } == DEFAULT_RADARS.map { it.radarId }.sorted()

private inline fun <reified E : Enum<E>> enumNamed(value: Any?): E? = enumValues<E>().firstOrNull { it.name == value }
