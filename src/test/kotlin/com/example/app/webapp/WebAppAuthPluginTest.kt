package com.example.app.webapp

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebAppAuthPluginTest {
    @Test
    fun `allows request with valid init data via query`() =
        testApplication {
            bootstrapWebAppApi()

            val response =
                client.get("/api/miniapp/profile") {
                    parameter("initData", TestInitDataFactory.signedInitData())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("user=424242;chat=sender;auth=1700000000", response.bodyAsText())
        }

    @Test
    fun `extracts init data from json body`() =
        testApplication {
            bootstrapWebAppApi()

            val response =
                client.post("/api/miniapp/profile") {
                    headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                    setBody("""{"initData":"${TestInitDataFactory.signedInitData()}"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().startsWith("body=true;user=424242"))
        }

    @Test
    fun `rejects request with invalid signature`() =
        testApplication {
            bootstrapWebAppApi()

            val response =
                client.get("/api/miniapp/profile") {
                    parameter("initData", TestInitDataFactory.tamperedInitData())
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val json = Json.decodeFromString<JsonObject>(response.bodyAsText())
            assertEquals(403, json["status"]?.jsonPrimitive?.int)
            assertEquals("Invalid init data signature", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `rejects request when init data is missing`() =
        testApplication {
            bootstrapWebAppApi()

            val response = client.get("/api/miniapp/profile")

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val json = Json.decodeFromString<JsonObject>(response.bodyAsText())
            assertEquals("Mini app init data is required", json["error"]?.jsonPrimitive?.content)
        }

    private fun ApplicationTestBuilder.bootstrapWebAppApi() {
        application {
            install(DoubleReceive)
            install(ContentNegotiation) { json() }

            routing {
                route("/api/miniapp") {
                    install(WebAppAuthPlugin) {
                        botToken = TestInitDataFactory.BOT_TOKEN
                        clock = FIXED_CLOCK
                    }

                    get("/profile") {
                        val context = call.attributes[WebAppAuth.ContextKey]
                        val authDate = call.attributes[WebAppAuth.AuthDateKey]
                        val chatType =
                            if (call.attributes.contains(WebAppAuth.ChatTypeKey)) {
                                call.attributes[WebAppAuth.ChatTypeKey]
                            } else {
                                null
                            }
                        call.respondText(
                            "user=${context.userId};chat=${chatType ?: "-"};auth=${authDate.epochSecond}",
                        )
                    }

                    post("/profile") {
                        val body = call.receiveText()
                        val context = call.attributes[WebAppAuth.ContextKey]
                        call.respondText("body=${body.contains("initData")};user=${context.userId}")
                    }
                }
            }
        }
    }

    companion object {
        private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}
