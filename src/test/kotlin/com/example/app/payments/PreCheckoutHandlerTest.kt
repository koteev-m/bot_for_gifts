package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.payments.dto.PaymentPayload
import com.example.app.payments.STARS_CURRENCY_CODE
import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.telegram.PreCheckoutQueryDto
import com.example.giftsbot.telegram.UserDto
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreCheckoutHandlerTest {
    private val casesRepository = mockk<CasesRepository>()
    private val telegramApiClient = mockk<TelegramApiClient>()
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var handler: PreCheckoutHandler

    @BeforeEach
    fun setUp() {
        resetMetrics()
        meterRegistry = SimpleMeterRegistry()
        handler = PreCheckoutHandler(telegramApiClient, casesRepository, meterRegistry)
    }

    @AfterEach
    fun tearDown() {
        if (::meterRegistry.isInitialized) {
            meterRegistry.clear()
        }
    }

    @Test
    fun `answers ok when payload and amount match`() =
        runTest {
            val case = caseConfig(price = 299)
            every { casesRepository.get("silver") } returns case
            coEvery { telegramApiClient.answerPreCheckoutQuery(any(), any(), any()) } returns true

            val payload =
                PaymentPayload(
                    caseId = "silver",
                    userId = USER_ID,
                    nonce = "nonce-1",
                    ts = 123456789L,
                ).encode()
            val query =
                PreCheckoutQueryDto(
                    id = "pcq-1",
                    from = userDto(),
                    currency = STARS_CURRENCY_CODE,
                    total_amount = case.priceStars,
                    invoice_payload = payload,
                )

            handler.handle(updateId = 42L, query = query)

            coVerify(exactly = 1) { telegramApiClient.answerPreCheckoutQuery("pcq-1", true, null) }
            assertEquals(1.0, counterValue(result = RESULT_OK))
            assertEquals(0.0, counterValue(result = RESULT_FAIL))
        }

    @Test
    fun `rejects when amount differs from case price`() =
        runTest {
            val case = caseConfig(price = 999)
            every { casesRepository.get("gold") } returns case
            coEvery { telegramApiClient.answerPreCheckoutQuery(any(), any(), any()) } returns true

            val payload =
                PaymentPayload(
                    caseId = "gold",
                    userId = USER_ID,
                    nonce = "nonce-2",
                    ts = 987654321L,
                ).encode()
            val query =
                PreCheckoutQueryDto(
                    id = "pcq-2",
                    from = userDto(),
                    currency = STARS_CURRENCY_CODE,
                    total_amount = case.priceStars + 1,
                    invoice_payload = payload,
                )

            handler.handle(updateId = 84L, query = query)

            coVerify(exactly = 1) {
                telegramApiClient.answerPreCheckoutQuery("pcq-2", false, DEFAULT_ERROR_MESSAGE)
            }
            assertEquals(0.0, counterValue(result = RESULT_OK))
            assertEquals(1.0, counterValue(result = RESULT_FAIL))
        }

    @Test
    fun `rejects when payload cannot be decoded`() =
        runTest {
            every { casesRepository.get(any()) } returns null
            coEvery { telegramApiClient.answerPreCheckoutQuery(any(), any(), any()) } returns true

            val query =
                PreCheckoutQueryDto(
                    id = "pcq-3",
                    from = userDto(),
                    currency = STARS_CURRENCY_CODE,
                    total_amount = 29,
                    invoice_payload = "not-a-json",
                )

            handler.handle(updateId = 126L, query = query)

            coVerify(exactly = 1) {
                telegramApiClient.answerPreCheckoutQuery("pcq-3", false, DEFAULT_ERROR_MESSAGE)
            }
            assertEquals(0.0, counterValue(result = RESULT_OK))
            assertEquals(1.0, counterValue(result = RESULT_FAIL))
        }

    private fun counterValue(result: String): Double =
        Metrics
            .counter(
                meterRegistry,
                MetricsNames.PRE_CHECKOUT_TOTAL,
                MetricsTags.COMPONENT to COMPONENT_VALUE,
                MetricsTags.RESULT to result,
            ).count()

    private fun caseConfig(price: Long): CaseConfig =
        CaseConfig(
            id = "ignored",
            title = "Case",
            priceStars = price,
            rtpExtMin = 0.0,
            rtpExtMax = 0.0,
            jackpotAlpha = 0.0,
            items = emptyList(),
        )

    private fun userDto(): UserDto =
        UserDto(
            id = USER_ID,
            is_bot = false,
            first_name = "Test",
        )

    private fun resetMetrics() {
        val metricsClass = Metrics::class.java
        listOf("counters", "timers", "longGauges", "intGauges").forEach { fieldName ->
            val field = metricsClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val map = field.get(Metrics) as? MutableMap<*, *>
            map?.clear()
        }
    }

    private companion object {
        private const val USER_ID = 555L
        private const val COMPONENT_VALUE = "payments"
        private const val RESULT_OK = "ok"
        private const val RESULT_FAIL = "fail"
        private const val DEFAULT_ERROR_MESSAGE = "Payment rejected: invalid parameters."
    }
}
