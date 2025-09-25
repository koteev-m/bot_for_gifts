package com.example.giftsbot.rng

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val HEX_PATTERN = Regex("^[0-9a-fA-F]+$")
private const val HEX_RADIX = 16
private const val HALF_BYTE_SHIFT = 4
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val HEX_CHAR_GROUP = 2
private const val MIN_KEY_LENGTH_BYTES = 32
private const val MAX_KEY_LENGTH_BYTES = 64

enum class FairnessKeyFormat {
    HEX,
    BASE64,
    UTF8,
}

fun detectKeyFormat(rawKey: String): FairnessKeyFormat {
    val trimmed = rawKey.trim()
    return when {
        isHexKey(trimmed) -> FairnessKeyFormat.HEX
        isBase64Key(trimmed) -> FairnessKeyFormat.BASE64
        else -> FairnessKeyFormat.UTF8
    }
}

fun decodeFairnessKey(rawKey: String): ByteArray =
    when (detectKeyFormat(rawKey)) {
        FairnessKeyFormat.HEX -> decodeHex(rawKey.trim())
        FairnessKeyFormat.BASE64 -> Base64.getDecoder().decode(rawKey.trim())
        FairnessKeyFormat.UTF8 -> rawKey.toByteArray(StandardCharsets.UTF_8)
    }

fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(key, "HmacSHA256")
    mac.init(secretKey)
    return mac.doFinal(data)
}

fun sha256(data: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data)
}

fun toHex(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and BYTE_MASK
        builder.append(Character.forDigit(value ushr HALF_BYTE_SHIFT, HEX_RADIX))
        builder.append(Character.forDigit(value and NIBBLE_MASK, HEX_RADIX))
    }
    return builder.toString()
}

fun fromHex(value: String): ByteArray = decodeHex(value)

private fun decodeHex(value: String): ByteArray {
    val cleaned = value.trim()
    require(cleaned.length % HEX_CHAR_GROUP == 0) { "Hex value must have even length" }
    val result = ByteArray(cleaned.length / HEX_CHAR_GROUP)
    var index = 0
    while (index < cleaned.length) {
        val high = Character.digit(cleaned[index], HEX_RADIX)
        val low = Character.digit(cleaned[index + 1], HEX_RADIX)
        require(high >= 0 && low >= 0) { "Invalid hex character" }
        result[index / HEX_CHAR_GROUP] = ((high shl HALF_BYTE_SHIFT) or low).toByte()
        index += HEX_CHAR_GROUP
    }
    return result
}

private fun isHexKey(value: String): Boolean {
    val hasEvenLength = value.length % HEX_CHAR_GROUP == 0
    val matchesHex = HEX_PATTERN.matches(value)
    val byteLength = value.length / HEX_CHAR_GROUP
    val withinRange = byteLength in MIN_KEY_LENGTH_BYTES..MAX_KEY_LENGTH_BYTES
    return hasEvenLength && matchesHex && withinRange
}

private fun isBase64Key(value: String): Boolean =
    try {
        val decoded = Base64.getDecoder().decode(value)
        decoded.size in MIN_KEY_LENGTH_BYTES..MAX_KEY_LENGTH_BYTES
    } catch (_: IllegalArgumentException) {
        false
    }
