package io.github.santiquiroz.blindside.shared.tak

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind

const val TAK_TELEMETRY_PATH = "/tak/telemetry"
const val TAK_TEAM_PATH = "/tak/team"
const val TAK_TELEMETRY_PERIOD_MS = 1_000L
const val TAK_TEAM_PERIOD_MS = 2_000L
const val TAK_LINK_FRESH_MS = 10_000L

data class TelemetryBlip(val id: Int, val bearingDeg: Double, val rangeM: Double, val confidence: Confidence)
data class Telemetry(val headingOk: Boolean, val blips: List<TelemetryBlip>, val points: Map<TacticalKind, GeoPoint>)
data class GeoFix(val point: GeoPoint, val accuracyM: Double)
enum class MateKind { PLAYER, STATION }

data class Mate(val callsign: String, val point: GeoPoint, val ageS: Int, val kind: MateKind = MateKind.PLAYER)
data class TeamUpdate(val self: GeoFix?, val mates: List<Mate>, val me: Long? = null)

fun encodeTelemetry(telemetry: Telemetry): String = MiniJson.obj(
    listOf(
        "v" to "1",
        "headingOk" to telemetry.headingOk.toString(),
        "blips" to MiniJson.array(telemetry.blips.map(::encodeBlip)),
        "points" to encodePoints(telemetry.points),
    ),
)

fun decodeTelemetry(json: String): Telemetry? {
    val fields = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    if (intField(fields, "v") != 1) return null
    val headingOk = fields["headingOk"] as? Boolean ?: return null
    val blips = fields["blips"] as? List<*> ?: return null
    val points = fields["points"] as? Map<*, *> ?: return null
    return Telemetry(headingOk, blips.mapNotNull(::decodeBlip), decodePoints(points))
}

fun encodeTeamUpdate(update: TeamUpdate): String = MiniJson.obj(
    listOf(
        "v" to "1",
        "self" to (update.self?.let(::encodeFix) ?: "null"),
        "mates" to MiniJson.array(update.mates.map(::encodeMate)),
        "me" to (update.me?.toString() ?: "null"),
    ),
)

fun decodeTeamUpdate(json: String): TeamUpdate? {
    val fields = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    if (intField(fields, "v") != 1) return null
    if (!fields.containsKey("self")) return null
    val rawSelf = fields["self"]
    val self = if (rawSelf == null) null else decodeFix(rawSelf) ?: return null
    val mates = fields["mates"] as? List<*> ?: return null
    val rawMe = fields["me"]
    val me = if (rawMe == null) null else longField(fields, "me") ?: return null
    return TeamUpdate(self, mates.mapNotNull(::decodeMate), me)
}

private fun encodeBlip(blip: TelemetryBlip): String = MiniJson.obj(
    listOf(
        "id" to blip.id.toString(),
        "bearing" to blip.bearingDeg.toString(),
        "range" to blip.rangeM.toString(),
        "conf" to MiniJson.quote(blip.confidence.name),
    ),
)

private fun decodeBlip(item: Any?): TelemetryBlip? {
    val fields = item as? Map<*, *> ?: return null
    val id = intField(fields, "id") ?: return null
    val bearing = doubleField(fields, "bearing") ?: return null
    val range = doubleField(fields, "range") ?: return null
    val confidence = confidenceOf(fields["conf"] as? String) ?: return null
    return TelemetryBlip(id, bearing, range, confidence)
}

private fun encodePoints(points: Map<TacticalKind, GeoPoint>): String = MiniJson.obj(
    points.toList().sortedBy { it.first.name }.map { (kind, point) ->
        kind.name to MiniJson.array(listOf(point.latDeg.toString(), point.lonDeg.toString()))
    },
)

private fun decodePoints(fields: Map<*, *>): Map<TacticalKind, GeoPoint> =
    fields.mapNotNull { (key, value) -> decodePoint(key as? String, value) }.toMap()

private fun decodePoint(key: String?, value: Any?): Pair<TacticalKind, GeoPoint>? {
    val kind = TacticalKind.entries.firstOrNull { it.name == key } ?: return null
    val coords = value as? List<*> ?: return null
    if (coords.size != 2) return null
    val lat = doubleOf(coords[0]) ?: return null
    val lon = doubleOf(coords[1]) ?: return null
    return kind to GeoPoint(lat, lon)
}

private fun encodeFix(fix: GeoFix): String = MiniJson.obj(
    listOf(
        "lat" to fix.point.latDeg.toString(),
        "lon" to fix.point.lonDeg.toString(),
        "acc" to fix.accuracyM.toString(),
    ),
)

private fun decodeFix(item: Any?): GeoFix? {
    val fields = item as? Map<*, *> ?: return null
    val lat = doubleField(fields, "lat") ?: return null
    val lon = doubleField(fields, "lon") ?: return null
    val acc = doubleField(fields, "acc") ?: return null
    return GeoFix(GeoPoint(lat, lon), acc)
}

private fun encodeMate(mate: Mate): String = MiniJson.obj(
    listOf(
        "cs" to MiniJson.quote(mate.callsign),
        "lat" to mate.point.latDeg.toString(),
        "lon" to mate.point.lonDeg.toString(),
        "age" to mate.ageS.toString(),
    ) + kindField(mate.kind),
)

private fun kindField(kind: MateKind): List<Pair<String, String>> =
    if (kind == MateKind.STATION) listOf("k" to MiniJson.quote("s")) else emptyList()

private fun mateKindOfCode(code: Any?): MateKind = if (code == "s") MateKind.STATION else MateKind.PLAYER

private fun decodeMate(item: Any?): Mate? {
    val fields = item as? Map<*, *> ?: return null
    val callsign = fields["cs"] as? String ?: return null
    val lat = doubleField(fields, "lat") ?: return null
    val lon = doubleField(fields, "lon") ?: return null
    val age = intField(fields, "age") ?: return null
    return Mate(callsign, GeoPoint(lat, lon), age, mateKindOfCode(fields["k"]))
}

private fun confidenceOf(name: String?): Confidence? =
    Confidence.entries.firstOrNull { it.name == name }

// Numbers arrive as Double from MiniJson today, but a Long must read the same.
private fun doubleOf(value: Any?): Double? = (value as? Number)?.toDouble()

private fun doubleField(fields: Map<*, *>, key: String): Double? = doubleOf(fields[key])

// A fractional id or version is malformed, not a number to round.
private fun intField(fields: Map<*, *>, key: String): Int? =
    doubleField(fields, key)?.takeIf { it % 1.0 == 0.0 }?.toInt()

// "me" is an unsigned 32-bit id that fits in a Long; MiniJson numbers arrive as Double.
private fun longField(fields: Map<*, *>, key: String): Long? =
    doubleField(fields, key)?.takeIf { it % 1.0 == 0.0 && it in 0.0..MAX_UINT32 }?.toLong()

private const val MAX_UINT32 = 4_294_967_295.0
