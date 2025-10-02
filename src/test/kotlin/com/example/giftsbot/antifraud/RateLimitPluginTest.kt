package com.example.giftsbot.antifraud

import com.example.giftsbot.testutil.assertMetricContains
import com.example.giftsbot.testutil.getMetricsText
import com.example.giftsbot.testutil.withAntifraudTestSetup
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val json = Json { ignoreUnknownKeys = false }

class RateLimitPluginTest {
    @Test
    fun `ip limit blocks second request`() =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                withAntifraudTestSetup(
                    includePaths = listOf("/ping"),
                    installPaymentsAndWebhook = false,
                )
            }

            val ok = client.get("/ping") { header("X-Forwarded-For", "203.0.113.1") }
            assertEquals(HttpStatusCode.OK, ok.status)

            val limited = client.get("/ping") { header("X-Forwarded-For", "203.0.113.1") }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)

            val payload = json.decodeFromString(RateLimitError.serializer(), limited.bodyAsText())
            assertEquals("rate_limited", payload.error)
            assertEquals(429, payload.status)
            assertEquals("ip", payload.type)
            assertTrue(payload.retryAfterSeconds > 0)

            val metrics = getMetricsText(client)
            assertMetricContains(metrics, "af_rl_blocked_total{type=\"ip\"}")
            assertMetricContains(metrics, "af_rl_retry_after_seconds_count")
        }

    @Test
    fun `subject limit blocks repeated subject`() =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                withAntifraudTestSetup(
                    includePaths = listOf("/ping"),
                    rlIpBurst = 2,
                    installPaymentsAndWebhook = false,
                )
            }

            val ok =
                client.get("/ping") {
                    header("X-Subject-Id", "42")
                }
            assertEquals(HttpStatusCode.OK, ok.status)

            val limited =
                client.get("/ping") {
                    header("X-Subject-Id", "42")
                }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)

            val payload = json.decodeFromString(RateLimitError.serializer(), limited.bodyAsText())
            assertEquals("subject", payload.type)
            assertTrue(payload.retryAfterSeconds > 0)

            val metrics = getMetricsText(client)
            assertMetricContains(metrics, "af_rl_blocked_total{type=\"subject\"}")
        }

    @Test
    fun `exclude path bypasses limiter`() =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                withAntifraudTestSetup(
                    includePaths = listOf("/ping", "/ping/excluded"),
                    excludePaths = listOf("/ping/excluded"),
                    installPaymentsAndWebhook = false,
                )
            }

            repeat(3) {
                val response = client.get("/ping/excluded")
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `trust proxy reads forwarded header`() =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                withAntifraudTestSetup(
                    trustProxy = true,
                    includePaths = listOf("/ping"),
                    installPaymentsAndWebhook = false,
                )
            }

            val first = client.get("/ping") { header("X-Forwarded-For", "198.51.100.1") }
            assertEquals(HttpStatusCode.OK, first.status)

            val second = client.get("/ping") { header("X-Forwarded-For", "198.51.100.2") }
            assertEquals(HttpStatusCode.OK, second.status)

            val third = client.get("/ping") { header("X-Forwarded-For", "198.51.100.2") }
            assertEquals(HttpStatusCode.TooManyRequests, third.status)
        }

    @Test
    fun `no proxy falls back to remote host`() =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                withAntifraudTestSetup(
                    trustProxy = false,
                    includePaths = listOf("/ping"),
                    installPaymentsAndWebhook = false,
                )
            }

            val first = client.get("/ping") { header("X-Forwarded-For", "198.51.100.1") }
            assertEquals(HttpStatusCode.OK, first.status)

            val second = client.get("/ping") { header("X-Forwarded-For", "198.51.100.2") }
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
        }
}

@Serializable
private data class RateLimitError(
    val error: String,
    val status: Int,
    val requestId: String,
    val type: String,
    val retryAfterSeconds: Long,
)
