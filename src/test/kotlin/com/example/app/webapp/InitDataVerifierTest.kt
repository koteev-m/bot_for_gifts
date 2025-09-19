package com.example.app.webapp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class InitDataVerifierTest {
    private val botToken = "123456:TEST-TOKEN"
    private val baseParameters =
        mapOf(
            "auth_date" to listOf("1700000000"),
            "query_id" to listOf("AAAbbb"),
            "user" to listOf("""{"id":424242,"username":"tester"}"""),
        )

    @Test
    fun `returns true for valid init data`() {
        val hash = InitDataVerifier.calculateHash(baseParameters, botToken)
        val initData = buildInitDataString(baseParameters + mapOf("hash" to listOf(hash)))

        assertTrue(InitDataVerifier.verify(initData, botToken))
    }

    @Test
    fun `returns false for payload with mismatched hash`() {
        val hash = InitDataVerifier.calculateHash(baseParameters, botToken)
        val tamperedParameters = baseParameters.toMutableMap().also { it["query_id"] = listOf("tampered") }
        val tamperedInitData = buildInitDataString(tamperedParameters + mapOf("hash" to listOf(hash)))

        assertFalse(InitDataVerifier.verify(tamperedInitData, botToken))
    }

    @Test
    fun `returns false when hash is absent`() {
        val initData = buildInitDataString(baseParameters)

        assertFalse(InitDataVerifier.verify(initData, botToken))
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
