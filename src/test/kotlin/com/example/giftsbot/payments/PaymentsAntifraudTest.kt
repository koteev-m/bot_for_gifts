package com.example.giftsbot.payments

import com.example.giftsbot.antifraud.velocity.ScoringThresholds
import com.example.giftsbot.antifraud.velocity.VelocityChecker
import com.example.giftsbot.antifraud.velocity.VelocityConfig
import com.example.giftsbot.telegram.ChatDto
import com.example.giftsbot.telegram.MessageDto
import com.example.giftsbot.telegram.PreCheckoutQueryDto
import com.example.giftsbot.telegram.SuccessfulPaymentDto
import com.example.giftsbot.telegram.UpdateDto
import com.example.giftsbot.telegram.UserDto
import com.example.giftsbot.testutil.AfTestComponents
import com.example.giftsbot.testutil.MiniAppInvoiceRequest
import com.example.giftsbot.testutil.TestClock
import com.example.giftsbot.testutil.assertMetricContains
import com.example.giftsbot.testutil.assertMetricContainsAny
import com.example.giftsbot.testutil.getMetricsText
import com.example.giftsbot.testutil.withAntifraudTestSetup
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val WEBHOOK_PATH = "/telegram/webhook"
private const val WEBHOOK_SECRET = "test-secret"

private val json = Json { ignoreUnknownKeys = true }

class PaymentsAntifraudTest {
    @Test
    fun `invoice hard block prevents creation`() =
        testApplication {
            val clock = TestClock()
            val velocity = aggressiveVelocity(clock)
            environment { config = MapApplicationConfig() }
            lateinit var components: AfTestComponents
            application {
                components =
                    withAntifraudTestSetup(
                        trustProxy = true,
                        includePaths = emptyList(),
                        rlIpBurst = 100,
                        rlSubjBurst = 100,
                        velocityChecker = velocity,
                    )
            }

            val okResponse =
                client.post("/api/miniapp/invoice") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header("X-Forwarded-For", "203.0.113.20")
                    setBody(json.encodeToString(MiniAppInvoiceRequest(caseId = "case", userId = 1)))
                }
            assertEquals(HttpStatusCode.OK, okResponse.status)

            val blocked =
                client.post("/api/miniapp/invoice") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header("X-Forwarded-For", "203.0.113.20")
                    setBody(json.encodeToString(MiniAppInvoiceRequest(caseId = "case", userId = 1)))
                }
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            val payload = json.parseToJsonElement(blocked.bodyAsText()).jsonObject
            assertEquals("rate_limited", payload.getValue("error").jsonPrimitive.content)
            assertEquals("velocity", payload.getValue("type").jsonPrimitive.content)
            assertEquals(1, components.invoiceService.callCount())

            val metrics = getMetricsText(client)
            assertMetricContains(metrics, "pay_af_blocks_total{type=\"invoice\"}")
        }

    @Test
    fun `pre checkout hard block answers limited`() =
        testApplication {
            val clock = TestClock()
            val velocity = aggressiveVelocity(clock)
            environment { config = MapApplicationConfig() }
            lateinit var components: AfTestComponents
            application {
                components =
                    withAntifraudTestSetup(
                        trustProxy = true,
                        includePaths = emptyList(),
                        rlIpBurst = 100,
                        rlSubjBurst = 100,
                        velocityChecker = velocity,
                    )
            }

            val update =
                UpdateDto(
                    update_id = 1000L,
                    pre_checkout_query =
                        PreCheckoutQueryDto(
                            id = "query-1",
                            from = UserDto(id = 77, is_bot = false, first_name = "User"),
                            currency = "XTR",
                            total_amount = 100,
                            invoice_payload = "payload",
                        ),
                )
            val response =
                client.post(WEBHOOK_PATH) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header("X-Telegram-Bot-Api-Secret-Token", WEBHOOK_SECRET)
                    header(HttpHeaders.UserAgent, "test-agent")
                    header("X-Forwarded-For", "203.0.113.30")
                    setBody(json.encodeToString(update))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val lastAnswer = components.telegramClient.lastPreCheckoutAnswer
            assertEquals("query-1", lastAnswer?.first)
            assertEquals(false, lastAnswer?.second)

            val metrics = getMetricsText(client)
            assertMetricContains(metrics, "pay_af_blocks_total{type=\"precheckout\"}")
        }

    @Test
    fun `successful payment logs once and is idempotent`() =
        testApplication {
            val clock = TestClock()
            val velocity = aggressiveVelocity(clock)
            environment { config = MapApplicationConfig() }
            lateinit var components: AfTestComponents
            application {
                components =
                    withAntifraudTestSetup(
                        trustProxy = true,
                        includePaths = emptyList(),
                        rlIpBurst = 100,
                        rlSubjBurst = 100,
                        velocityChecker = velocity,
                    )
            }

            val update = successUpdate(chargeId = "charge-1")
            repeat(2) {
                client.post(WEBHOOK_PATH) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header("X-Telegram-Bot-Api-Secret-Token", WEBHOOK_SECRET)
                    header("X-Forwarded-For", "203.0.113.40")
                    setBody(json.encodeToString(update))
                }
            }

            val processed = components.paymentProcessor.processedChargeIds()
            assertEquals(1, processed.size)
            assertEquals("charge-1", processed.first())
            assertEquals(1, components.paymentProcessor.duplicateCount())

            val metrics = getMetricsText(client)
            assertMetricContainsAny(
                metrics,
                listOf(
                    "pay_af_decisions_total{action=\"LOG_ONLY\",type=\"success\"}",
                    "pay_af_decisions_total{action=\"SOFT_CAP\",type=\"success\"}",
                ),
            )
        }

    @Test
    fun `webhook always ok and records flags`() =
        testApplication {
            val clock = TestClock()
            val velocity = aggressiveVelocity(clock)
            environment { config = MapApplicationConfig() }
            lateinit var components: AfTestComponents
            application {
                components =
                    withAntifraudTestSetup(
                        trustProxy = true,
                        includePaths = emptyList(),
                        rlIpBurst = 100,
                        rlSubjBurst = 100,
                        velocityChecker = velocity,
                    )
            }

            val update = messageUpdate()
            repeat(2) {
                val response =
                    client.post(WEBHOOK_PATH) {
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        header("X-Telegram-Bot-Api-Secret-Token", WEBHOOK_SECRET)
                        header("X-Forwarded-For", "203.0.113.50")
                        setBody(json.encodeToString(update))
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

            val metrics = getMetricsText(client)
            assertMetricContains(metrics, "pay_af_decisions_total{action=\"LOG_ONLY\",type=\"webhook\"}")
            assertMetricContains(metrics, "pay_af_decisions_total{action=\"SOFT_CAP\",type=\"webhook\"}")
            assertMetricContains(metrics, "pay_af_flags_total")
        }
}

private fun aggressiveVelocity(clock: TestClock): VelocityChecker =
    VelocityChecker(
        config =
            VelocityConfig(
                shortWindowSec = 60,
                longWindowSec = 60,
                ipShortMax = 1,
                ipLongMax = 1,
                subjectShortMax = 1,
                subjectLongMax = 1,
                distinctPathsShortMax = 1,
                uaTtlSeconds = 3600,
                subjectUaMismatchMax = 2,
                invoiceShortMax = 1,
                invoiceLongMax = 1,
                precheckoutShortMax = 1,
                precheckoutLongMax = 1,
                successShortMax = 1,
                successLongMax = 1,
            ),
        thresholds = ScoringThresholds(softCap = 10, hardBlock = 20),
        clock = clock,
    )

private fun successUpdate(chargeId: String): UpdateDto =
    UpdateDto(
        update_id = 2000L,
        message =
            MessageDto(
                message_id = 1,
                date = 1,
                chat = ChatDto(id = 1, type = "private"),
                from = UserDto(id = 55, is_bot = false, first_name = "User"),
                successful_payment =
                    SuccessfulPaymentDto(
                        currency = "XTR",
                        total_amount = 500,
                        invoice_payload = "payload",
                        telegram_payment_charge_id = chargeId,
                        provider_payment_charge_id = "provider-$chargeId",
                    ),
            ),
    )

private fun messageUpdate(): UpdateDto =
    UpdateDto(
        update_id = 3000L,
        message =
            MessageDto(
                message_id = 2,
                date = 2,
                chat = ChatDto(id = 2, type = "private"),
                from = UserDto(id = 60, is_bot = false, first_name = "User"),
                text = "hello",
            ),
    )
