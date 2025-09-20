package com.example.app.webapp

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TestInitDataFactory {
    const val BOT_TOKEN: String = "123456:TEST-TOKEN"

    val BASE_PARAMETERS: Map<String, List<String>> =
        mapOf(
            "auth_date" to listOf("1700000000"),
            "query_id" to listOf("AAAbbb"),
            "user" to listOf("""{"id":424242,"username":"tester"}"""),
            "chat_type" to listOf("sender"),
        )

    fun signedInitData(
        overrides: Map<String, List<String>> = emptyMap(),
        botToken: String = BOT_TOKEN,
        baseParameters: Map<String, List<String>> = BASE_PARAMETERS,
    ): String {
        val payload = (baseParameters + overrides).filterKeys { it != "hash" }
        val hash = InitDataVerifier.calculateHash(payload, botToken)
        val finalParameters = payload + mapOf("hash" to listOf(hash))
        return buildInitDataString(finalParameters)
    }

    fun tamperedInitData(): String {
        val altered = BASE_PARAMETERS.toMutableMap()
        altered["query_id"] = listOf("tampered")
        val hash = InitDataVerifier.calculateHash(BASE_PARAMETERS, BOT_TOKEN)
        val finalParameters = altered + mapOf("hash" to listOf(hash))
        return buildInitDataString(finalParameters)
    }

    private fun buildInitDataString(parameters: Map<String, List<String>>): String {
        val encoded = mutableListOf<String>()
        parameters.forEach { (key, values) ->
            values.forEach { value ->
                encoded += "${encode(key)}=${encode(value)}"
            }
        }
        return encoded.joinToString("&")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
