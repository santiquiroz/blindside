package io.github.santiquiroz.blindside.phone.settings

import io.github.santiquiroz.blindside.core.protocol.MiniJson

enum class LinkSupport { UNKNOWN, SINGLE_LINK, DUAL_LINK }

private const val DUAL_LINK_MAJOR = 0
private const val DUAL_LINK_MINOR = 2

// Firmware 0.2.0 adds "conns" to info (plan 04 Task 6); 0.1.0 holds one link and one bond, so the phone would take the watch's place.
fun linkSupportOf(infoJson: String): LinkSupport {
    val root = MiniJson.parseOrNull(infoJson) as? Map<*, *> ?: return LinkSupport.UNKNOWN
    val firmware = root["fw"] as? String
    return when {
        root["conns"] is List<*> || firmwareAtLeast(firmware, DUAL_LINK_MAJOR, DUAL_LINK_MINOR) -> LinkSupport.DUAL_LINK
        firmware != null -> LinkSupport.SINGLE_LINK
        else -> LinkSupport.UNKNOWN
    }
}

// A garbled read says nothing and must not forget a firmware already seen.
fun linkSupportAfterInfo(previous: LinkSupport, infoJson: String?): LinkSupport =
    infoJson?.let(::linkSupportOf)?.takeIf { it != LinkSupport.UNKNOWN } ?: previous

fun firmwareAtLeast(version: String?, major: Int, minor: Int): Boolean {
    val numbers = version?.split('.')?.take(2)?.map { it.toIntOrNull() ?: return false } ?: return false
    if (numbers.size < 2) return false
    return compareValuesBy(numbers, listOf(major, minor), { it[0] }, { it[1] }) >= 0
}
