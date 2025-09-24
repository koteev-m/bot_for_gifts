package com.example.app.telegram

import com.example.app.testutil.testAdmin
import com.example.giftsbot.telegram.TelegramApiClient
import com.example.giftsbot.telegram.WebhookInfoDto
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ADMIN_TOKEN_HEADER = "X-Admin-Token"
private const val ADMIN_TOKEN = "test-admin-token"
private const val PUBLIC_BASE_URL = "https://public.example/base/"
private const val WEBHOOK_PATH = "/tg/hook"
private const val WEBHOOK_SECRET = "test-webhook-secret"

class AdminRoutesTest {
    @Test
    fun `rejects requests without valid admin token`() =
        testApplication {
            val api = mockk<TelegramApiClient>(relaxed = true)
            application { bootstrapAdmin(api) }

            val responses =
                listOf(
                    client.post("/internal/telegram/webhook/set"),
                    client.post("/internal/telegram/webhook/set") {
                        header(ADMIN_TOKEN_HEADER, "invalid-$ADMIN_TOKEN")
                    },
                )

            responses.forEach { response ->
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.Unauthorized, response.status, body)
                val payload = Json.parseToJsonElement(body).jsonObject
                assertEquals("unauthorized", payload["error"]?.jsonPrimitive?.content)
                assertEquals(401, payload["status"]?.jsonPrimitive?.int)
                val requestId = payload["requestId"]?.jsonPrimitive?.content
                assertFalse(requestId.isNullOrBlank())
                assertEquals(response.headers["X-Request-Id"], requestId)
            }

            confirmVerified(api)
        }

    @Test
    fun `set webhook builds url from config when request omits url`() =
        testApplication {
            val api = mockk<TelegramApiClient>()
            val allowedUpdates = listOf("message", "callback_query")
            val maxConnections = 25
            val expectedUrl = "https://public.example/base/tg/hook"

            coEvery {
                api.setWebhook(
                    url = expectedUrl,
                    secretToken = WEBHOOK_SECRET,
                    allowedUpdates = allowedUpdates,
                    maxConnections = maxConnections,
                    dropPendingUpdates = false,
                )
            } returns true

            application { bootstrapAdmin(api) }

            val response =
                client.post("/internal/telegram/webhook/set") {
                    header(ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                    setBody(
                        TextContent(
                            """{"allowedUpdates":["message","callback_query"],"maxConnections":$maxConnections}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status, body)
            val payload = Json.parseToJsonElement(body).jsonObject
            assertTrue(payload["ok"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals(expectedUrl, payload["url"]?.jsonPrimitive?.content)
            assertEquals(
                allowedUpdates,
                payload["allowedUpdates"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals(maxConnections, payload["maxConnections"]?.jsonPrimitive?.int)

            coVerify(exactly = 1) {
                api.setWebhook(
                    url = expectedUrl,
                    secretToken = WEBHOOK_SECRET,
                    allowedUpdates = allowedUpdates,
                    maxConnections = maxConnections,
                    dropPendingUpdates = false,
                )
            }
            confirmVerified(api)
        }

    @Test
    fun `delete webhook forwards dropPending flag`() =
        testApplication {
            val api = mockk<TelegramApiClient>()
            val dropPending = true

            coEvery { api.deleteWebhook(dropPending) } returns true

            application { bootstrapAdmin(api) }

            val response =
                client.post("/internal/telegram/webhook/delete?dropPending=true") {
                    header(ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status, body)
            val payload = Json.parseToJsonElement(body).jsonObject
            assertTrue(payload["ok"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals(dropPending, payload["dropPending"]?.jsonPrimitive?.booleanOrNull)

            coVerify(exactly = 1) { api.deleteWebhook(dropPending) }
            confirmVerified(api)
        }

    @Test
    fun `info endpoint proxies webhook info response`() =
        testApplication {
            val api = mockk<TelegramApiClient>()
            val info =
                WebhookInfoDto(
                    url = "https://public.example/base/tg/hook",
                    has_custom_certificate = true,
                    pending_update_count = 7,
                    ip_address = "10.0.0.1",
                    last_error_date = 1_700_000_000,
                    last_error_message = "something went wrong",
                    last_synchronization_error_date = 1_700_000_123,
                    max_connections = 30,
                    allowed_updates = listOf("message"),
                )

            coEvery { api.getWebhookInfo() } returns info

            application { bootstrapAdmin(api) }

            val response =
                client.get("/internal/telegram/webhook/info") {
                    header(ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status, body)
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals(info.url, payload["url"]?.jsonPrimitive?.content)
            assertEquals(
                info.has_custom_certificate,
                payload["hasCustomCertificate"]?.jsonPrimitive?.booleanOrNull,
            )
            assertEquals(info.pending_update_count, payload["pendingUpdateCount"]?.jsonPrimitive?.int)
            assertEquals(info.ip_address, payload["ipAddress"]?.jsonPrimitive?.content)
            assertEquals(info.last_error_date, payload["lastErrorDate"]?.jsonPrimitive?.int)
            assertEquals(info.last_error_message, payload["lastErrorMessage"]?.jsonPrimitive?.content)
            assertEquals(
                info.last_synchronization_error_date,
                payload["lastSynchronizationErrorDate"]?.jsonPrimitive?.int,
            )
            assertEquals(info.max_connections, payload["maxConnections"]?.jsonPrimitive?.int)
            assertEquals(
                info.allowed_updates,
                payload["allowedUpdates"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertFalse(payload.containsKey("secretToken"))

            coVerify(exactly = 1) { api.getWebhookInfo() }
            confirmVerified(api)
        }
}

private fun Application.bootstrapAdmin(
    api: TelegramApiClient,
    baseUrl: String = PUBLIC_BASE_URL,
    path: String = WEBHOOK_PATH,
    secret: String = WEBHOOK_SECRET,
) {
    testAdmin(
        api = api,
        adminToken = ADMIN_TOKEN,
        publicBaseUrl = baseUrl,
        webhookPath = path,
        webhookSecret = secret,
    )
}
