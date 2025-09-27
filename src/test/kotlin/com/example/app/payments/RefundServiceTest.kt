package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.giftsbot.telegram.TelegramApiClient
import com.example.giftsbot.telegram.TelegramApiException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefundServiceTest {
    @MockK
    private lateinit var telegramApiClient: TelegramApiClient

    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var clock: Clock
    private lateinit var refundService: RefundService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        meterRegistry = SimpleMeterRegistry()
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
        refundService = RefundService(telegramApiClient, meterRegistry, clock)
    }

    @Test
    fun `successful refund recorded and idempotent`() =
        runTest {
            coEvery {
                telegramApiClient.refundStarPayment(
                    userId = 10,
                    telegramPaymentChargeId = "charge",
                )
            } returns Unit
            val successBefore = metricCount(MetricsNames.REFUND_TOTAL)
            val failBefore = metricCount(MetricsNames.REFUND_FAIL_TOTAL)

            refundService.refundStarPayment(
                userId = 10,
                telegramPaymentChargeId = "charge",
                reason = RefundReason.Draw("test"),
            )
            refundService.refundStarPayment(
                userId = 10,
                telegramPaymentChargeId = "charge",
                reason = RefundReason.Draw("test"),
            )

            assertEquals(successBefore + 1.0, metricCount(MetricsNames.REFUND_TOTAL))
            assertEquals(failBefore, metricCount(MetricsNames.REFUND_FAIL_TOTAL))
            coVerify(exactly = 1) { telegramApiClient.refundStarPayment(10, "charge") }
        }

    @Test
    fun `failed refund increments metrics and retries succeed`() =
        runTest {
            coEvery {
                telegramApiClient.refundStarPayment(
                    userId = 20,
                    telegramPaymentChargeId = "charge-2",
                )
            } throws TelegramApiException("502") andThen Unit
            val successBefore = metricCount(MetricsNames.REFUND_TOTAL)
            val failBefore = metricCount(MetricsNames.REFUND_FAIL_TOTAL)

            assertFailsWith<TelegramApiException> {
                refundService.refundStarPayment(
                    userId = 20,
                    telegramPaymentChargeId = "charge-2",
                    reason = RefundReason.Validation("fail"),
                )
            }

            assertEquals(failBefore + 1.0, metricCount(MetricsNames.REFUND_FAIL_TOTAL))
            assertEquals(successBefore, metricCount(MetricsNames.REFUND_TOTAL))

            refundService.refundStarPayment(
                userId = 20,
                telegramPaymentChargeId = "charge-2",
                reason = RefundReason.Validation("fail"),
            )

            assertEquals(successBefore + 1.0, metricCount(MetricsNames.REFUND_TOTAL))
            coVerify(exactly = 2) { telegramApiClient.refundStarPayment(20, "charge-2") }
        }

    private fun metricCount(name: String): Double =
        Metrics.counter(meterRegistry, name, MetricsTags.COMPONENT to "payments").count()
}
