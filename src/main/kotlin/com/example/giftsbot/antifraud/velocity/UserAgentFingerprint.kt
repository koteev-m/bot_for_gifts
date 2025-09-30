package com.example.giftsbot.antifraud.velocity

private val EDGE_MARKERS = listOf("edg/", "edge/")
private val CHROME_MARKERS = listOf("chrome/", "crios/", "chromium/")
private val FIREFOX_MARKERS = listOf("firefox/", "fxios/")
private val SAFARI_MARKERS = listOf("version/")
private const val TELEGRAM_MARKER = "telegram"
private const val BOT_MARKER = "bot"
private const val SAFARI_KEYWORD = "safari"

fun parseUserAgentFingerprint(ua: String?): String? {
    if (ua.isNullOrBlank()) {
        return null
    }
    val normalized = ua.trim().lowercase()
    val fingerprint =
        when {
            normalized.contains(TELEGRAM_MARKER) -> "tg_webapp"
            normalized.contains(BOT_MARKER) -> "bot"
            else -> detectBrowserFingerprint(normalized)
        }
    return fingerprint
}

private fun detectBrowserFingerprint(normalized: String): String {
    val edgeVersion = extractVersion(normalized, EDGE_MARKERS)
    val chromeVersion = if (edgeVersion == null) extractVersion(normalized, CHROME_MARKERS) else null
    val firefoxVersion = extractVersion(normalized, FIREFOX_MARKERS)
    val safariVersion =
        if (normalized.contains(SAFARI_KEYWORD)) {
            extractVersion(normalized, SAFARI_MARKERS)
        } else {
            null
        }
    return when {
        edgeVersion != null -> "edge_$edgeVersion"
        chromeVersion != null -> "ch_$chromeVersion"
        firefoxVersion != null -> "ff_$firefoxVersion"
        safariVersion != null -> "sf_$safariVersion"
        else -> "unk"
    }
}

private fun extractVersion(
    normalized: String,
    markers: List<String>,
): String? {
    for (marker in markers) {
        val version = extractMajor(normalized, marker)
        if (version != null) {
            return version
        }
    }
    return null
}

private fun extractMajor(
    normalized: String,
    marker: String,
): String? {
    val index = normalized.indexOf(marker)
    if (index < 0) {
        return null
    }
    var cursor = index + marker.length
    var invalid = false
    while (cursor < normalized.length && !normalized[cursor].isDigit()) {
        if (!isVersionDelimiter(normalized[cursor])) {
            invalid = true
            break
        }
        cursor++
    }
    val hasDigits = !invalid && cursor < normalized.length && normalized[cursor].isDigit()
    return if (!hasDigits) {
        null
    } else {
        val start = cursor
        while (cursor < normalized.length && normalized[cursor].isDigit()) {
            cursor++
        }
        normalized.substring(start, cursor)
    }
}

private fun isVersionDelimiter(value: Char): Boolean =
    value.isLetter() || value == ' ' || value == '.' || value == '_' || value == '/'
