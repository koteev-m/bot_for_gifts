package com.example.giftsbot.antifraud

import com.example.giftsbot.antifraud.store.InMemoryBucketStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CONFIG_INCLUDE_PATHS = "app.antifraud.rl.includePaths"
private const val CONFIG_EXCLUDE_PATHS = "app.antifraud.rl.excludePaths"
private const val CONFIG_TRUST_PROXY = "app.antifraud.rl.trustProxy"
private const val CONFIG_RETRY_AFTER = "app.antifraud.rl.retryAfter"
private const val CONFIG_IP_ENABLED = "app.antifraud.rl.ip.enabled"
private const val CONFIG_IP_BURST = "app.antifraud.rl.ip.capacity"
private const val CONFIG_IP_RPS = "app.antifraud.rl.ip.rps"
private const val CONFIG_IP_TTL = "app.antifraud.rl.ip.ttlSeconds"
private const val CONFIG_SUBJECT_ENABLED = "app.antifraud.rl.subject.enabled"
private const val CONFIG_SUBJECT_BURST = "app.antifraud.rl.subject.capacity"
private const val CONFIG_SUBJECT_RPS = "app.antifraud.rl.subject.rps"
private const val CONFIG_SUBJECT_TTL = "app.antifraud.rl.subject.ttlSeconds"

class RateLimitPluginTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `ip limit blocks second request`() {
        testApplication {
            val clock = TestClock()
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = InMemoryBucketStore()
            configureEnvironment {
                put(CONFIG_INCLUDE_PATHS, "/test")
                put(CONFIG_EXCLUDE_PATHS, "")
                put(CONFIG_TRUST_PROXY, "true")
                put(CONFIG_RETRY_AFTER, "42")
                put(CONFIG_IP_ENABLED, "true")
                put(CONFIG_IP_BURST, "1")
                put(CONFIG_IP_RPS, "0")
                put(CONFIG_IP_TTL, "60")
                put(CONFIG_SUBJECT_ENABLED, "false")
                put(CONFIG_SUBJECT_BURST, "1")
                put(CONFIG_SUBJECT_RPS, "0")
                put(CONFIG_SUBJECT_TTL, "60")
            }
            configureApplication(meterRegistry, store, clock)

            val okResponse =
                client.get("/test") {
                    header("X-Forwarded-For", "203.0.113.1")
                }
            assertEquals(HttpStatusCode.OK, okResponse.status)

            val blockedResponse =
                client.get("/test") {
                    header("X-Forwarded-For", "203.0.113.1")
                }
            assertEquals(HttpStatusCode.TooManyRequests, blockedResponse.status)
            assertEquals("42", blockedResponse.headers[HttpHeaders.RetryAfter])
            val payload = json.decodeFromString<RateLimitError>(blockedResponse.bodyAsText())
            assertEquals("rate_limited", payload.error)
            assertEquals(429, payload.status)
            assertEquals("ip", payload.type)
            assertEquals(42, payload.retryAfterSeconds)
        }
    }

    @Test
    fun `subject limit blocks when subject exceeds tokens`() {
        testApplication {
            val clock = TestClock()
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = InMemoryBucketStore()
            configureEnvironment {
                put(CONFIG_INCLUDE_PATHS, "/test")
                put(CONFIG_EXCLUDE_PATHS, "")
                put(CONFIG_TRUST_PROXY, "false")
                put(CONFIG_RETRY_AFTER, "30")
                put(CONFIG_IP_ENABLED, "true")
                put(CONFIG_IP_BURST, "5")
                put(CONFIG_IP_RPS, "5")
                put(CONFIG_IP_TTL, "60")
                put(CONFIG_SUBJECT_ENABLED, "true")
                put(CONFIG_SUBJECT_BURST, "1")
                put(CONFIG_SUBJECT_RPS, "0")
                put(CONFIG_SUBJECT_TTL, "60")
            }
            configureApplication(meterRegistry, store, clock)

            val okResponse =
                client.get("/test") {
                    header("X-Subject-Id", "777")
                }
            assertEquals(HttpStatusCode.OK, okResponse.status)

            val blockedResponse =
                client.get("/test") {
                    header("X-Subject-Id", "777")
                }
            assertEquals(HttpStatusCode.TooManyRequests, blockedResponse.status)
            val payload = json.decodeFromString<RateLimitError>(blockedResponse.bodyAsText())
            assertEquals("subject", payload.type)
            assertEquals(30, payload.retryAfterSeconds)
        }
    }

    @Test
    fun `excluded path bypasses rate limiting`() {
        testApplication {
            val clock = TestClock()
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = InMemoryBucketStore()
            configureEnvironment {
                put(CONFIG_INCLUDE_PATHS, "/test")
                put(CONFIG_EXCLUDE_PATHS, "/test/excluded")
                put(CONFIG_TRUST_PROXY, "true")
                put(CONFIG_RETRY_AFTER, "60")
                put(CONFIG_IP_ENABLED, "true")
                put(CONFIG_IP_BURST, "1")
                put(CONFIG_IP_RPS, "0")
                put(CONFIG_IP_TTL, "60")
                put(CONFIG_SUBJECT_ENABLED, "true")
                put(CONFIG_SUBJECT_BURST, "1")
                put(CONFIG_SUBJECT_RPS, "0")
                put(CONFIG_SUBJECT_TTL, "60")
            }
            configureApplication(meterRegistry, store, clock)

            repeat(3) {
                val response =
                    client.get("/test/excluded") {
                        header("X-Forwarded-For", "198.51.100.1")
                        header("X-Subject-Id", "100")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }
    }

    @Test
    fun `both buckets enforce limits independently`() {
        testApplication {
            val clock = TestClock()
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = InMemoryBucketStore()
            configureEnvironment {
                put(CONFIG_INCLUDE_PATHS, "/test")
                put(CONFIG_EXCLUDE_PATHS, "")
                put(CONFIG_TRUST_PROXY, "true")
                put(CONFIG_RETRY_AFTER, "15")
                put(CONFIG_IP_ENABLED, "true")
                put(CONFIG_IP_BURST, "5")
                put(CONFIG_IP_RPS, "0")
                put(CONFIG_IP_TTL, "60")
                put(CONFIG_SUBJECT_ENABLED, "true")
                put(CONFIG_SUBJECT_BURST, "1")
                put(CONFIG_SUBJECT_RPS, "0")
                put(CONFIG_SUBJECT_TTL, "60")
            }
            configureApplication(meterRegistry, store, clock)

            val okResponse =
                client.get("/test") {
                    header("X-Forwarded-For", "192.0.2.10")
                    header("X-Subject-Id", "55")
                }
            assertEquals(HttpStatusCode.OK, okResponse.status)

            val blockedResponse =
                client.get("/test") {
                    header("X-Forwarded-For", "192.0.2.10")
                    header("X-Subject-Id", "55")
                }
            assertEquals(HttpStatusCode.TooManyRequests, blockedResponse.status)
            val payload = json.decodeFromString<RateLimitError>(blockedResponse.bodyAsText())
            assertEquals("subject", payload.type)
            assertEquals(15, payload.retryAfterSeconds)
        }
    }

    @Test
    fun `metrics expose rate limit counters`() {
        testApplication {
            val clock = TestClock()
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val store = InMemoryBucketStore()
            configureEnvironment {
                put(CONFIG_INCLUDE_PATHS, "/test")
                put(CONFIG_EXCLUDE_PATHS, "")
                put(CONFIG_TRUST_PROXY, "true")
                put(CONFIG_RETRY_AFTER, "25")
                put(CONFIG_IP_ENABLED, "true")
                put(CONFIG_IP_BURST, "1")
                put(CONFIG_IP_RPS, "0")
                put(CONFIG_IP_TTL, "60")
                put(CONFIG_SUBJECT_ENABLED, "false")
                put(CONFIG_SUBJECT_BURST, "1")
                put(CONFIG_SUBJECT_RPS, "0")
                put(CONFIG_SUBJECT_TTL, "60")
            }
            configureApplication(meterRegistry, store, clock)

            client.get("/test") {
                header("X-Forwarded-For", "203.0.113.77")
            }
            client.get("/test") {
                header("X-Forwarded-For", "203.0.113.77")
            }

            val body = client.get("/metrics").bodyAsText()
            assertTrue(body.lineSequence().any { line -> line.contains("af_rl_blocked_total") })
            assertTrue(body.lineSequence().any { line -> line.contains("af_rl_retry_after_seconds_count") })
        }
    }

    private fun ApplicationTestBuilder.configureEnvironment(block: MapApplicationConfig.() -> Unit) {
        environment {
            config = MapApplicationConfig().apply(block)
        }
    }

    private fun ApplicationTestBuilder.configureApplication(
        meterRegistry: PrometheusMeterRegistry,
        store: InMemoryBucketStore,
        clock: Clock,
    ) {
        application {
            install(ContentNegotiation) {
                json()
            }
            installAntifraudIntegration(
                meterRegistry = meterRegistry,
                store = store,
                clock = clock,
            )
            routing {
                get("/test") {
                    call.respondText("OK")
                }
                get("/test/excluded") {
                    call.respondText("OK")
                }
                get("/metrics") {
                    call.respondText(meterRegistry.scrape())
                }
            }
        }
    }
}

private class TestClock : Clock {
    override fun nowMillis(): Long = current

    private var current: Long = 0L
}

@Serializable
private data class RateLimitError(
    val error: String,
    val status: Int,
    val requestId: String,
    val type: String,
    val retryAfterSeconds: Long,
)
