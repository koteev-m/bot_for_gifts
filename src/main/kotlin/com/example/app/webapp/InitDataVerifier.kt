package com.example.app.webapp

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object InitDataVerifier {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SECRET_MESSAGE = "WebAppData"
    private val UTF8 = StandardCharsets.UTF_8

    fun verify(
        initData: String,
        botToken: String,
    ): Boolean {
        if (initData.isBlank() || botToken.isBlank()) {
            return false
        }
        val parsed = parse(initData)
        val hash = parsed.hash
        return hash != null && verify(parsed.parameters, hash, botToken)
    }

    internal fun parse(initData: String): ParsedInitData {
        if (initData.isBlank()) {
            return ParsedInitData(emptyMap(), null)
        }

        val parameters = mutableMapOf<String, MutableList<String>>()
        initData.split('&').forEach { pair ->
            if (pair.isBlank()) {
                return@forEach
            }
            val separatorIndex = pair.indexOf('=')
            val rawKey = if (separatorIndex >= 0) pair.substring(0, separatorIndex) else pair
            val rawValue = if (separatorIndex >= 0) pair.substring(separatorIndex + 1) else ""

            val key = URLDecoder.decode(rawKey, UTF8)
            val value = URLDecoder.decode(rawValue, UTF8)
            parameters.getOrPut(key) { mutableListOf() }.add(value)
        }

        val hash = parameters.remove("hash")?.firstOrNull()

        return ParsedInitData(parameters.mapValues { entry -> entry.value.toList() }, hash)
    }

    internal fun verify(
        parameters: Map<String, List<String>>,
        hash: String,
        botToken: String,
    ): Boolean {
        if (hash.isBlank() || botToken.isBlank()) {
            return false
        }

        val normalizedHash = hash.lowercase(Locale.US)
        val secretKey = hmac(botToken.toByteArray(UTF8), SECRET_MESSAGE.toByteArray(UTF8))
        val dataCheckString = dataCheckString(parameters)
        val calculatedHash = hmac(secretKey, dataCheckString.toByteArray(UTF8)).toHex()

        return MessageDigest.isEqual(
            calculatedHash.toByteArray(UTF8),
            normalizedHash.toByteArray(UTF8),
        )
    }

    internal fun calculateHash(
        parameters: Map<String, List<String>>,
        botToken: String,
    ): String {
        val secretKey = hmac(botToken.toByteArray(UTF8), SECRET_MESSAGE.toByteArray(UTF8))
        val dataCheckString = dataCheckString(parameters)
        return hmac(secretKey, dataCheckString.toByteArray(UTF8)).toHex()
    }

    private fun dataCheckString(parameters: Map<String, List<String>>): String {
        val segments =
            parameters
                .entries
                .asSequence()
                .sortedBy { it.key }
                .flatMap { (key, values) ->
                    values
                        .asSequence()
                        .sorted()
                        .map { value -> "$key=$value" }
                }
        return segments.joinToString("\n")
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    data class ParsedInitData(
        val parameters: Map<String, List<String>>,
        val hash: String?,
    )
}
