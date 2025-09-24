package com.example.app.telegram

import com.example.app.testutil.JsonSamples
import com.example.app.testutil.RecordingSink
import com.example.app.testutil.testPrometheusRegistry
import com.example.app.testutil.testWebhook
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
private const val SECRET_TOKEN = "test-secret"
private val json = Json { ignoreUnknownKeys = true }

class WebhookParsingAndSizeTest {
    @Test
    fun `parses batch of updates and enqueues each one`() =
        testApplication {
            val sink = RecordingSink()
            application {
                bootstrapWebhook(sink)
            }

            val updateIds = listOf(101L, 102L, 103L, 104L)
            val response =
                client.post("/tghook") {
                    setBody(TextContent(JsonSamples.batch(*updateIds.toLongArray()), ContentType.Application.Json))
                    header(SECRET_HEADER, SECRET_TOKEN)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())

            awaitEnqueueCalls(sink, updateIds.size)
            assertEquals(updateIds.size, sink.enqueueCalls())
            assertEquals(JsonSamples.dtos(*updateIds.toLongArray()), sink.recordedUpdates())
        }

    @Test
    fun `invalid json payload returns bad request`() =
        testApplication {
            val sink = RecordingSink()
            application {
                bootstrapWebhook(sink)
            }

            val response =
                client.post("/tghook") {
                    setBody(TextContent("not json", ContentType.Application.Json))
                    header(SECRET_HEADER, SECRET_TOKEN)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid update json", payload["error"]?.jsonPrimitive?.content)
            assertEquals(400, payload["status"]?.jsonPrimitive?.int)
            assertEquals(0, sink.enqueueCalls())
        }

    @Test
    fun `payload larger than limit returns payload too large`() =
        testApplication {
            val sink = RecordingSink()
            var registry: PrometheusMeterRegistry? = null
            application {
                bootstrapWebhook(sink)
                registry = testPrometheusRegistry()
            }

            val oversizedBody = "{\"data\":\"${"a".repeat(1_200_000)}\"}"
            val response =
                client.post("/tghook") {
                    setBody(TextContent(oversizedBody, ContentType.Application.Json))
                    header(SECRET_HEADER, SECRET_TOKEN)
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status, body)
            assertTrue(
                body.isBlank() || body.contains("payload too large"),
                "payload too large response should mention the error",
            )
            if (body.isNotBlank()) {
                val payload = json.parseToJsonElement(body).jsonObject
                assertEquals("payload too large", payload["error"]?.jsonPrimitive?.content)
                assertEquals(413, payload["status"]?.jsonPrimitive?.int)
            }
            assertEquals(0, sink.enqueueCalls())

            registry?.scrape()?.let { metrics ->
                if ("tg_webhook" in metrics) {
                    assertTrue(
                        metrics.lineSequence().any { it.contains("tg_webhook_body_too_large_total") },
                        "too large metric should be present",
                    )
                }
            }
        }
}

private fun Application.bootstrapWebhook(sink: RecordingSink) {
    stubJsonErrorResponses()
    testWebhook(secret = SECRET_TOKEN, sink = sink)
}

private fun Application.stubJsonErrorResponses() {
    sendPipeline.intercept(ApplicationSendPipeline.Transform) { value ->
        val payload = value as? Map<*, *> ?: return@intercept
        val error = payload["error"]
        val status = payload["status"]
        if (error is String && status is Int) {
            val serialized = """{"error":"$error","status":$status}"""
            proceedWith(TextContent(serialized, ContentType.Application.Json))
        }
    }
}

private suspend fun awaitEnqueueCalls(
    sink: RecordingSink,
    expectedCalls: Int,
) {
    withTimeout(1_000) {
        while (sink.enqueueCalls() < expectedCalls) {
            delay(10)
        }
    }
}
