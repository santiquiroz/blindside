package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.config.toJson
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.toPipelineConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SessionClockStamp(val epochMs: Long, val elapsedNanos: Long)

data class RecordingMeta(
    val appVersion: String,
    val source: String,
    val stamp: SessionClockStamp,
    val device: String,
    val handedness: String,
    val screenMode: String,
    val vibrationUsage: String,
    val configJson: String,
    val amplitudeControl: Boolean,
    val primitives: Boolean,
    val infoJson: String? = null,
)

private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

fun recordingMeta(
    settings: AppSettings,
    source: String,
    stamp: SessionClockStamp,
    device: String,
    appVersion: String,
    amplitudeControl: Boolean,
    primitives: Boolean,
): RecordingMeta = RecordingMeta(
    appVersion = appVersion,
    source = source,
    stamp = stamp,
    device = device,
    handedness = settings.handedness.name,
    screenMode = settings.screenMode.name,
    vibrationUsage = settings.vibrationUsage.name,
    configJson = toPipelineConfig(settings).toJson(),
    amplitudeControl = amplitudeControl,
    primitives = primitives,
)

fun headerJson(meta: RecordingMeta): String = jsonObject(
    "format" to jsonString("blindside-wear"),
    "app_version" to jsonString(meta.appVersion),
    "source" to jsonString(meta.source),
    "started_epoch_ms" to meta.stamp.epochMs.toString(),
    "started_elapsed_ns" to meta.stamp.elapsedNanos.toString(),
    "device" to jsonString(meta.device),
    "handedness" to jsonString(meta.handedness),
    "screen_mode" to jsonString(meta.screenMode),
    "vibration_usage" to jsonString(meta.vibrationUsage),
    "config" to meta.configJson,
    "amplitude_control" to meta.amplitudeControl.toString(),
    "primitives" to meta.primitives.toString(),
    "info" to infoField(meta.infoJson),
)

fun recordingFileName(epochMs: Long, zone: ZoneId, source: String): String =
    "blindside-${source.lowercase()}-${FILE_STAMP.format(Instant.ofEpochMilli(epochMs).atZone(zone))}.bsrec"

fun jsonString(value: String): String = value.map(::escapeJsonChar).joinToString("", "\"", "\"")

// The belt's info is embedded as-is; anything that is not an object stays a string so the header stays valid JSON.
fun infoField(json: String?): String = when {
    json == null -> "null"
    looksLikeJsonObject(json) -> json.trim()
    else -> jsonString(json)
}

private fun looksLikeJsonObject(json: String): Boolean = json.trim().let { it.startsWith("{") && it.endsWith("}") }

private fun jsonObject(vararg fields: Pair<String, String>): String =
    fields.joinToString(",", "{", "}") { (name, value) -> "${jsonString(name)}:$value" }

private fun escapeJsonChar(c: Char): String = when {
    c == '"' -> "\\\""
    c == '\\' -> "\\\\"
    c < ' ' -> "\\u%04x".format(c.code)
    else -> c.toString()
}
