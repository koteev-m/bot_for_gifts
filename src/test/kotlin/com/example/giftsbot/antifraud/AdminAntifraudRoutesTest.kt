package com.example.giftsbot.antifraud

import com.example.giftsbot.antifraud.store.InMemoryBucketStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
private const val CONFIG_ADMIN_TOKEN = "app.antifraud.admin.token"
private const val CONFIG_BAN_TTL = "app.antifraud.ban.defaultTtlSeconds"
private const val ADMIN_HEADER = "X-Admin-Token"
private const val ADMIN_TOKEN_VALUE = "secret-token"
private const val METRIC_SUSPICIOUS = "af_ip_suspicious_mark_total"
private const val METRIC_TEMP_BAN = "af_ip_ban_total{type=\"temp\"}"
private const val METRIC_PERM_BAN = "af_ip_ban_total{type=\"perm\"}"
private const val METRIC_UNBAN = "af_ip_unban_total"
private const val METRIC_FORBIDDEN = "af_ip_forbidden_total"

class AdminAntifraudRoutesTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `admin routes manage suspicious ips and bans`() {
        testApplication {
            val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val bucketStore = InMemoryBucketStore()
            val suspiciousStore = InMemorySuspiciousIpStore()
            configureEnvironment {
                put(CONFIG_INCLUDE_PATHS, "/protected")
                put(CONFIG_EXCLUDE_PATHS, "")
                put(CONFIG_TRUST_PROXY, "true")
                put(CONFIG_RETRY_AFTER, "60")
                put(CONFIG_IP_ENABLED, "true")
                put(CONFIG_IP_BURST, "5")
                put(CONFIG_IP_RPS, "1")
                put(CONFIG_IP_TTL, "60")
                put(CONFIG_SUBJECT_ENABLED, "false")
                put(CONFIG_SUBJECT_BURST, "1")
                put(CONFIG_SUBJECT_RPS, "0")
                put(CONFIG_SUBJECT_TTL, "60")
                put(CONFIG_ADMIN_TOKEN, ADMIN_TOKEN_VALUE)
                put(CONFIG_BAN_TTL, "120")
            }
            configureApplication(meterRegistry, bucketStore, suspiciousStore)

            assertUnauthorizedAccess()

            val suspiciousIp = "198.51.100.10"
            markSuspiciousIp(suspiciousIp, reason = "suspicious")
            assertRecentContains(suspiciousIp)

            val tempBannedIp = "198.51.100.11"
            banIp(tempBannedIp, ttlSeconds = 10, reason = "temp")
            assertTempBanEnforced(tempBannedIp)

            val permBannedIp = "198.51.100.12"
            banIp(permBannedIp, ttlSeconds = 0, reason = "perm")
            assertPermBanEnforced(permBannedIp)

            unbanIp(permBannedIp)
            assertPermAccessRestored(permBannedIp)
            assertBannedList(tempBannedIp, permBannedIp)

            assertMetrics()
            assertLazyEviction(suspiciousStore)
        }
    }

    private suspend fun ApplicationTestBuilder.assertUnauthorizedAccess() {
        val payload = MarkSuspiciousRequest(ip = "203.0.113.10")
        val response =
            client.post("/internal/antifraud/ip/mark-suspicious") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(json.encodeToString(MarkSuspiciousRequest.serializer(), payload))
            }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = json.decodeFromString(AdminAntifraudErrorResponse.serializer(), response.bodyAsText())
        assertEquals("unauthorized", body.error)

        val forbidden =
            client.post("/internal/antifraud/ip/mark-suspicious") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(ADMIN_HEADER, "wrong")
                setBody(json.encodeToString(MarkSuspiciousRequest.serializer(), payload))
            }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        val forbiddenBody = json.decodeFromString(AdminAntifraudErrorResponse.serializer(), forbidden.bodyAsText())
        assertEquals("forbidden", forbiddenBody.error)
    }

    private suspend fun ApplicationTestBuilder.markSuspiciousIp(
        ip: String,
        reason: String?,
    ) {
        val request = MarkSuspiciousRequest(ip = ip, reason = reason)
        val response =
            client.post("/internal/antifraud/ip/mark-suspicious") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(ADMIN_HEADER, ADMIN_TOKEN_VALUE)
                setBody(json.encodeToString(MarkSuspiciousRequest.serializer(), request))
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val entry = json.decodeFromString(IpEntry.serializer(), response.bodyAsText())
        assertEquals(IpStatus.SUSPICIOUS, entry.status)
        assertEquals(reason, entry.reason)
    }

    private suspend fun ApplicationTestBuilder.assertRecentContains(ip: String) {
        val response =
            client.get("/internal/antifraud/ip/list") {
                header(ADMIN_HEADER, ADMIN_TOKEN_VALUE)
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val entries = json.decodeFromString(ListSerializer(IpEntry.serializer()), response.bodyAsText())
        assertTrue(entries.any { entry -> entry.ip == ip })
    }

    private suspend fun ApplicationTestBuilder.banIp(
        ip: String,
        ttlSeconds: Long,
        reason: String?,
    ) {
        val request = BanIpRequest(ip = ip, ttlSeconds = ttlSeconds, reason = reason)
        val response =
            client.post("/internal/antifraud/ip/ban") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(ADMIN_HEADER, ADMIN_TOKEN_VALUE)
                setBody(json.encodeToString(BanIpRequest.serializer(), request))
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val entry = json.decodeFromString(IpEntry.serializer(), response.bodyAsText())
        if (ttlSeconds == 0L) {
            assertEquals(IpStatus.PERM_BANNED, entry.status)
            assertNull(entry.expiresAtMs)
        } else {
            assertEquals(IpStatus.TEMP_BANNED, entry.status)
            assertNotNull(entry.expiresAtMs)
        }
    }

    private suspend fun ApplicationTestBuilder.assertTempBanEnforced(ip: String) {
        val response =
            client.get("/protected") {
                header("X-Forwarded-For", ip)
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = json.decodeFromString(BannedIpError.serializer(), response.bodyAsText())
        assertEquals("ip_banned", body.error)
        assertNotNull(body.retryAfterSeconds)
    }

    private suspend fun ApplicationTestBuilder.assertPermBanEnforced(ip: String) {
        val response =
            client.get("/protected") {
                header("X-Forwarded-For", ip)
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = json.decodeFromString(BannedIpError.serializer(), response.bodyAsText())
        assertEquals("ip_banned", body.error)
        assertNull(body.retryAfterSeconds)
    }

    private suspend fun ApplicationTestBuilder.unbanIp(ip: String) {
        val request = UnbanIpRequest(ip = ip)
        val response =
            client.post("/internal/antifraud/ip/unban") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(ADMIN_HEADER, ADMIN_TOKEN_VALUE)
                setBody(json.encodeToString(UnbanIpRequest.serializer(), request))
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString(UnbanIpResponse.serializer(), response.bodyAsText())
        assertTrue(body.ok)
    }

    private suspend fun ApplicationTestBuilder.assertPermAccessRestored(ip: String) {
        val response =
            client.get("/protected") {
                header("X-Forwarded-For", ip)
            }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun ApplicationTestBuilder.assertBannedList(
        tempIp: String,
        removedIp: String,
    ) {
        val response =
            client.get("/internal/antifraud/ip/list?type=banned") {
                header(ADMIN_HEADER, ADMIN_TOKEN_VALUE)
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val entries = json.decodeFromString(ListSerializer(IpEntry.serializer()), response.bodyAsText())
        assertTrue(entries.any { entry -> entry.ip == tempIp })
        assertFalse(entries.any { entry -> entry.ip == removedIp })
    }

    private suspend fun ApplicationTestBuilder.assertMetrics() {
        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.lineSequence().any { line -> line.contains(METRIC_SUSPICIOUS) })
        assertTrue(body.lineSequence().any { line -> line.contains(METRIC_TEMP_BAN) })
        assertTrue(body.lineSequence().any { line -> line.contains(METRIC_PERM_BAN) })
        assertTrue(body.lineSequence().any { line -> line.contains(METRIC_UNBAN) })
        assertTrue(body.lineSequence().any { line -> line.contains(METRIC_FORBIDDEN) })
    }

    private fun assertLazyEviction(store: SuspiciousIpStore) {
        val baseTime = 1_000L
        val ip = "198.51.100.99"
        val created = store.ban(ip, ttlSeconds = 1, reason = "expiring", nowMs = baseTime)
        assertEquals(IpStatus.TEMP_BANNED, created.status)
        val (active, _) = store.isBanned(ip, nowMs = baseTime)
        assertTrue(active)
        val (expired, _) = store.isBanned(ip, nowMs = baseTime + 5_000)
        assertFalse(expired)
        val remaining = store.listBanned(nowMs = baseTime + 5_000)
        assertTrue(remaining.none { entry -> entry.ip == ip })
    }

    private fun ApplicationTestBuilder.configureEnvironment(block: MapApplicationConfig.() -> Unit) {
        environment {
            config = MapApplicationConfig().apply(block)
        }
    }

    private fun ApplicationTestBuilder.configureApplication(
        meterRegistry: PrometheusMeterRegistry,
        bucketStore: InMemoryBucketStore,
        suspiciousStore: SuspiciousIpStore,
    ) {
        application {
            install(ContentNegotiation) {
                json()
            }
            installAntifraudIntegration(
                meterRegistry = meterRegistry,
                store = bucketStore,
                suspiciousIpStore = suspiciousStore,
            )
            routing {
                get("/protected") {
                    call.respondText("OK")
                }
                get("/metrics") {
                    call.respondText(meterRegistry.scrape())
                }
            }
        }
    }

    @Serializable
    private data class BannedIpError(
        val error: String,
        val status: Int,
        val requestId: String,
        val retryAfterSeconds: Long?,
    )
}
