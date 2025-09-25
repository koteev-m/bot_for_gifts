package com.example.giftsbot.economy

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsTags
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ADMIN_TOKEN = "test-admin-token"
private const val ADMIN_HEADER = "X-Admin-Token"
private val json = Json { ignoreUnknownKeys = true }

class EconomyRoutesTest {
    @Test
    fun `public cases endpoint returns safe payload`() =
        withEconomyApplication { repo, _ ->
            repo.reload()

            val response = client.get("/api/miniapp/cases")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])

            val array = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, array.size)

            val caseJson = array.first().jsonObject
            val keys = caseJson.keys

            assertTrue(setOf("id", "title", "priceStars").all(keys::contains))
            assertTrue(!keys.contains("thumbnail") || caseJson["thumbnail"] == JsonNull)
            assertTrue("items" !in keys)
            assertTrue(keys.none { it.contains("slot", ignoreCase = true) })
            assertTrue(keys.none { it.contains("rtp", ignoreCase = true) })

            val dto = json.decodeFromJsonElement(PublicCaseDto.serializer(), array.first())
            assertEquals("alpha", dto.id)
            assertEquals("Alpha", dto.title)
            assertEquals(100L, dto.priceStars)
            assertEquals(null, dto.thumbnail)
        }

    @Test
    fun `admin preview rejects missing or invalid token`() =
        withEconomyApplication { repo, _ ->
            repo.reload()

            val missingTokenResponse =
                client.get("/internal/economy/preview") {
                    parameter("caseId", "alpha")
                }
            assertUnauthorizedOrForbidden(missingTokenResponse.status)
            val missingError = json.parseToJsonElement(missingTokenResponse.bodyAsText()).jsonObject
            assertEquals("missing_admin_token", missingError["error"]?.jsonPrimitive?.content)

            val invalidTokenResponse =
                client.get("/internal/economy/preview") {
                    header(ADMIN_HEADER, "invalid")
                    parameter("caseId", "alpha")
                }
            assertEquals(HttpStatusCode.Forbidden, invalidTokenResponse.status)
            val invalidError = json.parseToJsonElement(invalidTokenResponse.bodyAsText()).jsonObject
            assertEquals("invalid_admin_token", invalidError["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `admin preview returns case data with valid token`() =
        withEconomyApplication { repo, _ ->
            repo.reload()

            val response =
                client.get("/internal/economy/preview") {
                    header(ADMIN_HEADER, ADMIN_TOKEN)
                    parameter("caseId", "alpha")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val preview = json.decodeFromString<CasePreview>(response.bodyAsText())
            assertEquals("alpha", preview.caseId)
            assertEquals(100L, preview.priceStars)
            assertEquals(25.0, preview.evExt)
            assertEquals(0.25, preview.rtpExt)
            assertEquals(1_000_000, preview.sumPpm)
            assertEquals(0.01, preview.alpha)
        }

    @Test
    fun `admin reload enforces token protection`() =
        withEconomyApplication { repo, _ ->
            repo.reload()

            val missingTokenResponse = client.post("/internal/economy/reload")
            assertUnauthorizedOrForbidden(missingTokenResponse.status)
            val missingError = json.parseToJsonElement(missingTokenResponse.bodyAsText()).jsonObject
            assertEquals("missing_admin_token", missingError["error"]?.jsonPrimitive?.content)

            val invalidTokenResponse =
                client.post("/internal/economy/reload") {
                    header(ADMIN_HEADER, "invalid")
                }
            assertEquals(HttpStatusCode.Forbidden, invalidTokenResponse.status)
            val invalidError = json.parseToJsonElement(invalidTokenResponse.bodyAsText()).jsonObject
            assertEquals("invalid_admin_token", invalidError["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `admin reload returns validation summary and bumps metrics`() =
        withEconomyApplication { repo, registry ->
            repo.reload()
            val reloadCounter = Metrics.counter(registry, "economy_reload_total", MetricsTags.RESULT to "ok")
            val initialReloads = reloadCounter.count()

            val response =
                client.post("/internal/economy/reload") {
                    header(ADMIN_HEADER, ADMIN_TOKEN)
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            val summary = json.decodeFromString<CasesValidationSummary>(response.bodyAsText())
            assertEquals(1, summary.total)
            assertEquals(1, summary.ok)
            assertEquals(0, summary.failed)
            assertTrue(summary.reports.all { it.isOk })
            assertEquals("alpha", summary.reports.single().caseId)

            assertEquals(initialReloads + 1.0, reloadCounter.count())
        }
}

private fun assertUnauthorizedOrForbidden(status: HttpStatusCode) {
    assertTrue(
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden,
        "Expected 401 or 403 but got $status",
    )
}

private fun withEconomyApplication(
    adminToken: String = ADMIN_TOKEN,
    root: CasesRoot = sampleRoot(),
    block: suspend ApplicationTestBuilder.(repo: CasesRepository, registry: SimpleMeterRegistry) -> Unit,
) {
    val registry = SimpleMeterRegistry()
    val repo = CasesRepository(registry) { root }

    testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { economyRoutes(repo, adminToken) }
        }

        block(repo, registry)
    }
}

private fun sampleRoot(): CasesRoot =
    CasesRoot(
        cases =
            listOf(
                CaseConfig(
                    id = "alpha",
                    title = "Alpha",
                    priceStars = 100,
                    rtpExtMin = 0.2,
                    rtpExtMax = 0.8,
                    jackpotAlpha = 0.01,
                    items =
                        listOf(
                            PrizeItemConfig(
                                id = "alpha-prize",
                                type = CaseSlotType.GIFT,
                                starCost = 50,
                                probabilityPpm = 500_000,
                            ),
                            PrizeItemConfig(
                                id = "alpha-internal",
                                type = CaseSlotType.INTERNAL,
                                probabilityPpm = 500_000,
                            ),
                        ),
                ),
            ),
    )
