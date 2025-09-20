package com.example.app.miniapp

import com.example.app.api.miniapp.MiniCaseDto
import com.example.app.registerMiniAppApiRoutes
import com.example.app.webapp.TestInitDataFactory
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.text.Charsets

class MiniAppCasesRouteTest {
    @Test
    fun `returns mini cases with no-store cache control`() =
        testApplication {
            val yaml =
                """
                cases:
                  - id: "alpha"
                    title: "Alpha"
                    price_stars: 29
                    thumbnail: "https://example.com/alpha.png"
                    short_description: "Alpha description"
                  - id: "beta"
                    title: "Beta"
                    price_stars: 59
                    thumbnail: "https://example.com/beta.png"
                    short_description: "Beta description"
                """.trimIndent()
            val service = MiniCasesConfigService(inputStreamProvider = { yaml.byteInputStream(Charsets.UTF_8) })

            application {
                install(DoubleReceive)
                install(ContentNegotiation) { json() }

                routing {
                    registerMiniAppApiRoutes(TestInitDataFactory.BOT_TOKEN, service)
                }
            }

            val response =
                client.get("/api/miniapp/cases") {
                    parameter("initData", TestInitDataFactory.signedInitData())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])

            val payload = Json.decodeFromString<List<MiniCaseDto>>(response.bodyAsText())
            assertEquals(2, payload.size)
            assertEquals("alpha", payload[0].id)
            assertEquals("beta", payload[1].id)
        }

    @Test
    fun `rejects request without init data`() =
        testApplication {
            val service = MiniCasesConfigService(inputStreamProvider = { EMPTY_YAML.byteInputStream(Charsets.UTF_8) })

            application {
                install(DoubleReceive)
                install(ContentNegotiation) { json() }

                routing {
                    registerMiniAppApiRoutes(TestInitDataFactory.BOT_TOKEN, service)
                }
            }

            val response = client.get("/api/miniapp/cases")

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    companion object {
        private const val EMPTY_YAML = "cases: []"
    }
}
