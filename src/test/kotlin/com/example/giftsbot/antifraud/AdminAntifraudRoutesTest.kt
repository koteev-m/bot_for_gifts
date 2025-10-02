package com.example.giftsbot.antifraud

import com.example.giftsbot.testutil.AfTestComponents
import com.example.giftsbot.testutil.assertMetricContains
import com.example.giftsbot.testutil.getMetricsText
import com.example.giftsbot.testutil.withAntifraudTestSetup
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ADMIN_HEADER = "X-Admin-Token"
private val json = Json { ignoreUnknownKeys = true }

class AdminAntifraudRoutesTest {
    @Test
    fun `admin endpoints manage bans and metrics`() =
        testApplication {
            environment { config = MapApplicationConfig() }
            lateinit var components: AfTestComponents
            application {
                components =
                    withAntifraudTestSetup(
                        adminToken = "secret-token",
                        trustProxy = true,
                        includePaths = listOf("/ping"),
                        installPaymentsAndWebhook = false,
                    )
            }

            val token = "secret-token"
            val suspiciousIp = "198.51.100.10"
            expectUnauthorizedMark(MarkSuspiciousRequest(ip = suspiciousIp, reason = "watch"))
            markSuspiciousIp(token, suspiciousIp)
            assertRecentContains(token, suspiciousIp)

            val tempIp = "198.51.100.11"
            banIp(token, tempIp, ttlSeconds = 5)
            val tempError = assertForbiddenIp(tempIp)
            assertEquals("ip_banned", tempError.error)
            assertNotNull(tempError.retryAfterSeconds)

            val permIp = "198.51.100.12"
            banIp(token, permIp, ttlSeconds = 0)
            val permError = assertForbiddenIp(permIp)
            assertEquals("ip_banned", permError.error)
            assertEquals(null, permError.retryAfterSeconds)

            unbanIp(token, permIp)
            assertAllowedIp(permIp)
            assertFalse(listBanned(token).any { it.ip == permIp })

            components.clock.advanceMs(6000)
            assertFalse(listBanned(token).any { it.ip == tempIp })
            assertAllowedIp(tempIp)

            val metrics = getMetricsText(client)
            assertMetricContains(metrics, "af_ip_ban_total")
            assertMetricContains(metrics, "af_ip_unban_total")
            assertMetricContains(metrics, "af_ip_forbidden_total")
        }

    private suspend fun ApplicationTestBuilder.expectUnauthorizedMark(request: MarkSuspiciousRequest) {
        val unauthorized =
            client.post("/internal/antifraud/ip/mark-suspicious") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(json.encodeToString(MarkSuspiciousRequest.serializer(), request))
            }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        val unauthorizedBody = json.decodeFromString(AdminErrorResponse.serializer(), unauthorized.bodyAsText())
        assertEquals("unauthorized", unauthorizedBody.error)
        assertEquals(401, unauthorizedBody.status)

        val forbidden =
            client.post("/internal/antifraud/ip/mark-suspicious") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(ADMIN_HEADER, "wrong")
                setBody(json.encodeToString(MarkSuspiciousRequest.serializer(), request))
            }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        val forbiddenBody = json.decodeFromString(AdminErrorResponse.serializer(), forbidden.bodyAsText())
        assertEquals("forbidden", forbiddenBody.error)
        assertEquals(403, forbiddenBody.status)
    }

    private suspend fun ApplicationTestBuilder.markSuspiciousIp(
        token: String,
        ip: String,
    ) {
        val response =
            client.post("/internal/antifraud/ip/mark-suspicious") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(ADMIN_HEADER, token)
                setBody(json.encodeToString(MarkSuspiciousRequest.serializer(), MarkSuspiciousRequest(ip = ip)))
            }
        val entry = json.decodeFromString(IpEntry.serializer(), response.bodyAsText())
        assertEquals(IpStatus.SUSPICIOUS, entry.status)
    }

    private suspend fun ApplicationTestBuilder.assertRecentContains(
        token: String,
        ip: String,
    ) {
        val response =
            client.get("/internal/antifraud/ip/list") {
                header(ADMIN_HEADER, token)
            }
        val entries = json.decodeFromString(ListSerializer(IpEntry.serializer()), response.bodyAsText())
        assertTrue(entries.any { it.ip == ip })
    }

    private suspend fun ApplicationTestBuilder.banIp(
        token: String,
        ip: String,
        ttlSeconds: Long,
    ) {
        val response =
            client.post("/internal/antifraud/ip/ban") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(ADMIN_HEADER, token)
                setBody(json.encodeToString(BanIpRequest.serializer(), BanIpRequest(ip = ip, ttlSeconds = ttlSeconds)))
            }
        val entry = json.decodeFromString(IpEntry.serializer(), response.bodyAsText())
        val expectedStatus = if (ttlSeconds == 0L) IpStatus.PERM_BANNED else IpStatus.TEMP_BANNED
        assertEquals(expectedStatus, entry.status)
    }

    private suspend fun ApplicationTestBuilder.unbanIp(
        token: String,
        ip: String,
    ) {
        val response =
            client.post("/internal/antifraud/ip/unban") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(ADMIN_HEADER, token)
                setBody(json.encodeToString(UnbanIpRequest.serializer(), UnbanIpRequest(ip = ip)))
            }
        val body = json.decodeFromString(UnbanIpResponse.serializer(), response.bodyAsText())
        assertTrue(body.ok)
    }

    private suspend fun ApplicationTestBuilder.assertForbiddenIp(ip: String): BannedIpError {
        val response = client.get("/ping") { header("X-Forwarded-For", ip) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        return json.decodeFromString(BannedIpError.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.assertAllowedIp(ip: String) {
        val response = client.get("/ping") { header("X-Forwarded-For", ip) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun ApplicationTestBuilder.listBanned(token: String): List<IpEntry> {
        val response =
            client.get("/internal/antifraud/ip/list?type=banned") {
                header(ADMIN_HEADER, token)
            }
        return json.decodeFromString(ListSerializer(IpEntry.serializer()), response.bodyAsText())
    }
}

@Serializable
private data class AdminErrorResponse(
    val error: String,
    val status: Int,
    val requestId: String?,
)

@Serializable
private data class BannedIpError(
    val error: String,
    val status: Int,
    val requestId: String?,
    val retryAfterSeconds: Long?,
)
