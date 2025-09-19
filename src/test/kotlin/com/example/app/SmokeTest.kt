package com.example.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val json = Json { ignoreUnknownKeys = true }

class SmokeTest {
    @Test
    fun `health endpoint returns ok`() =
        testApplication {
            application { module() }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }

    @Test
    fun `metrics endpoint exposes prometheus help`() =
        testApplication {
            application { module() }

            val body = client.get("/metrics").bodyAsText()

            assertTrue(body.lineSequence().any { it.startsWith("# HELP") })
        }

    @Test
    fun `version endpoint exposes app and version`() =
        testApplication {
            application { module() }

            val body = client.get("/version").bodyAsText()
            val jsonObject = json.parseToJsonElement(body).jsonObject

            assertTrue(jsonObject.containsKey("app"), "version payload should contain app key")
            assertTrue(jsonObject.containsKey("version"), "version payload should contain version key")
        }
}
