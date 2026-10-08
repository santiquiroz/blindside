package io.github.santiquiroz.blindside.phone.update

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import java.net.URL

data class ReleaseInfo(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkBytes: Long?,
    val watchApkUrl: String?,
    val notes: String,
)

fun parseLatestRelease(json: String): ReleaseInfo? {
    val root = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    val tag = root.string("tag_name")
    if (tag.isNullOrBlank()) return null
    val assets = root.objects("assets")
    val phone = assets.firstNamed(PHONE_APK_PREFIX)
    val watch = assets.firstNamed(WATCH_APK_PREFIX)
    return ReleaseInfo(
        version = tag,
        releaseUrl = root.string("html_url").orEmpty(),
        apkUrl = phone?.downloadUrl(),
        apkBytes = phone?.byteSize(),
        watchApkUrl = watch?.downloadUrl(),
        notes = root.string("body").orEmpty().take(500),
    )
}

fun isTrustedDownloadUrl(url: String): Boolean {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return false
    if (parsed.protocol != "https") return false
    val host = parsed.host.orEmpty().lowercase()
    // Leading dots so github.com.evil.io and evil-github.com never match.
    return host == "github.com" || host.endsWith(".githubusercontent.com")
}

private const val PHONE_APK_PREFIX = "blindside-celular"
private const val WATCH_APK_PREFIX = "blindside-reloj"
private const val APK_SUFFIX = ".apk"

private fun Map<*, *>.string(key: String): String? = this[key] as? String

private fun Map<*, *>.objects(key: String): List<Map<*, *>> =
    (this[key] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()

private fun List<Map<*, *>>.firstNamed(prefix: String): Map<*, *>? =
    firstOrNull {
        val name = it.string("name").orEmpty()
        name.startsWith(prefix) && name.endsWith(APK_SUFFIX)
    }

private fun Map<*, *>.downloadUrl(): String? =
    string("browser_download_url")?.takeIf { it.isNotBlank() }

private fun Map<*, *>.byteSize(): Long? = (this["size"] as? Number)?.toLong()
