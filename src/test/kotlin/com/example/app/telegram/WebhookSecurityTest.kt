package com.example.app.telegram

import com.example.app.testutil.JsonSamples
import com.example.app.testutil.RecordingSink
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
private const val SECRET_TOKEN = "test-secret"

class WebhookSecurityTest {
    @Test
    fun `rejects requests without valid secret token`() =
        testApplication {
            val sink = RecordingSink()
            application { bootstrapWebhook(sink) }

            val responses =
                listOf(null, "invalid-$SECRET_TOKEN").map { providedSecret ->
                    client.post("/tghook") {
                        setBody(TextContent(JsonSamples.singleUpdate(1), ContentType.Application.Json))
                        providedSecret?.let { header(SECRET_HEADER, it) }
                    }
                }

            responses.forEach { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
                val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("forbidden", payload["error"]?.jsonPrimitive?.content)
                assertEquals(403, payload["status"]?.jsonPrimitive?.int)
            }
            assertEquals(0, sink.enqueueCalls())
        }

    @Test
    fun `rejects unsupported content type`() =
        testApplication {
            val sink = RecordingSink()
            application { bootstrapWebhook(sink) }

            val response =
                client.post("/tghook") {
                    setBody(TextContent(JsonSamples.singleUpdate(2), ContentType.Text.Plain))
                    header(SECRET_HEADER, SECRET_TOKEN)
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, response.bodyAsText())
            assertEquals(0, sink.enqueueCalls())
        }

    @Test
    fun `accepts valid update and enqueues once`() =
        testApplication {
            val sink = RecordingSink()
            application { bootstrapWebhook(sink) }

            val response =
                client.post("/tghook") {
                    setBody(TextContent(JsonSamples.singleUpdate(7), ContentType.Application.Json))
                    header(SECRET_HEADER, SECRET_TOKEN)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())

            withTimeout(1_000) {
                while (sink.enqueueCalls() < 1) {
                    delay(10)
                }
            }
            assertEquals(1, sink.enqueueCalls())
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
            val json = """{"error":"$error","status":$status}"""
            proceedWith(TextContent(json, ContentType.Application.Json))
        }
    }
}
