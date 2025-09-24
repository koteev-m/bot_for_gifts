package com.example.giftsbot.economy

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
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
    fun `public cases endpoint returns safe data`() =
        testApplication {
            val repo = repository(sampleRoot()).apply { reload() }

            application {
                install(ContentNegotiation) { json() }

                routing {
                    economyRoutes(repo, ADMIN_TOKEN)
                }
            }

            val response = client.get("/api/miniapp/cases")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])

            val payload = json.decodeFromString<List<PublicCaseDto>>(response.bodyAsText())
            assertEquals(1, payload.size)
            val case = payload.first()
            assertEquals("alpha", case.id)
            assertEquals("Alpha", case.title)
            assertEquals(100L, case.priceStars)
            assertTrue(case.thumbnail == null)
        }

    @Test
    fun `admin preview requires token`() =
        testApplication {
            val repo = repository(sampleRoot()).apply { reload() }

            application {
                install(ContentNegotiation) { json() }

                routing {
                    economyRoutes(repo, ADMIN_TOKEN)
                }
            }

            val response =
                client.get("/internal/economy/preview") {
                    parameter("caseId", "alpha")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val error = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("missing_admin_token", error["error"]?.jsonPrimitive?.content)
            assertEquals(401, error["status"]?.jsonPrimitive?.int)
        }

    @Test
    fun `admin endpoints return data with valid token`() =
        testApplication {
            var loads = 0
            val repo =
                CasesRepository(SimpleMeterRegistry()) {
                    loads += 1
                    sampleRoot()
                }
            repo.reload()

            application {
                install(ContentNegotiation) { json() }

                routing {
                    economyRoutes(repo, ADMIN_TOKEN)
                }
            }

            val previewResponse =
                client.get("/internal/economy/preview") {
                    header(ADMIN_HEADER, ADMIN_TOKEN)
                    parameter("caseId", "alpha")
                }
            assertEquals(HttpStatusCode.OK, previewResponse.status)
            val preview = json.decodeFromString<CasePreview>(previewResponse.bodyAsText())
            assertEquals("alpha", preview.caseId)

            val reloadResponse =
                client.post("/internal/economy/reload") {
                    header(ADMIN_HEADER, ADMIN_TOKEN)
                }
            assertEquals(HttpStatusCode.OK, reloadResponse.status, reloadResponse.bodyAsText())
            val summary = json.decodeFromString<CasesValidationSummary>(reloadResponse.bodyAsText())
            assertEquals(1, summary.total)
            assertTrue(loads >= 2)
        }
}

private fun repository(root: CasesRoot): CasesRepository = CasesRepository(SimpleMeterRegistry()) { root }

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
