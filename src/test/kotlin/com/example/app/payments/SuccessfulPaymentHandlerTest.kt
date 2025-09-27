package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.payments.dto.PaymentPayload
import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CaseSlotType
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.economy.PrizeItemConfig
import com.example.giftsbot.rng.RngDrawRecord
import com.example.giftsbot.rng.RngDrawResult
import com.example.giftsbot.rng.RngReceipt
import com.example.giftsbot.rng.RngService
import com.example.giftsbot.telegram.ChatDto
import com.example.giftsbot.telegram.MessageDto
import com.example.giftsbot.telegram.SuccessfulPaymentDto
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SuccessfulPaymentHandlerTest {
    @MockK
    private lateinit var telegramApiClient: TelegramApiClient

    @MockK
    private lateinit var rngService: RngService

    @MockK
    private lateinit var casesRepository: CasesRepository

    @MockK
    private lateinit var awardService: AwardService

    @MockK
    private lateinit var refundService: RefundService

    private lateinit var meterRegistry: MeterRegistry

    private lateinit var handler: SuccessfulPaymentHandler

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        meterRegistry = SimpleMeterRegistry()
        val paymentsConfig =
            PaymentsConfig(
                currency = STARS_CURRENCY_CODE,
                titlePrefix = null,
                receiptEnabled = true,
                businessConnectionId = null,
            )
        val paymentSupport = PaymentSupport(config = paymentsConfig, refundService = refundService)
        handler =
            SuccessfulPaymentHandler(
                telegramApiClient = telegramApiClient,
                rngService = rngService,
                casesRepository = casesRepository,
                awardService = awardService,
                paymentSupport = paymentSupport,
                meterRegistry = meterRegistry,
            )
        coEvery { refundService.refundStarPayment(any(), any(), any()) } returns Unit
    }

    @Test
    fun `handle successful payment builds plan and sends receipt`() =
        runTest {
            val case = giftCase(id = "case-1", priceStars = 100, prizeId = "item-1")
            stubCase(case)
            val payload = PaymentPayload(caseId = case.id, userId = 777, nonce = "nonce", ts = 1L)
            val drawResult = rngResult(payload, resultItemId = "item-1", ppm = 123)
            stubDraw(payload, drawResult)
            stubSendMessage(payload.userId)
            val planSlot = slot<AwardPlan>()
            coEvery { awardService.schedule(capture(planSlot)) } returns Unit

            val message =
                paymentMessage(
                    chargeId = "charge-1",
                    payload = payload,
                    totalAmount = case.priceStars,
                    currency = STARS_CURRENCY_CODE,
                )

            val metricsBefore = metricsSnapshot()
            handler.handle(updateId = 1L, message = message)

            val capturedPlan = planSlot.captured
            assertNotNull(capturedPlan)
            assertPlanMatches(capturedPlan, payload, case.priceStars, drawResult, "charge-1")
            metricsBefore.assertDelta(success = 1.0, idempotent = 0.0, failure = 0.0)
            coVerify(exactly = 1) { awardService.schedule(any()) }
            coVerify(exactly = 1) { telegramApiClient.sendMessage(payload.userId, any(), true, message.message_id) }
        }

    @Test
    fun `duplicate payment is idempotent`() =
        runTest {
            val case = giftCase(id = "case-2", priceStars = 200, prizeId = "item-2")
            stubCase(case)
            val payload = PaymentPayload(caseId = case.id, userId = 900, nonce = "nonce", ts = 1L)
            val drawResult = rngResult(payload, resultItemId = "item-2", ppm = 456)
            stubDraw(payload, drawResult)
            stubSendMessage(payload.userId)
            coEvery { awardService.schedule(any()) } returns Unit

            val message =
                paymentMessage(
                    chargeId = "charge-dup",
                    payload = payload,
                    totalAmount = case.priceStars,
                    currency = STARS_CURRENCY_CODE,
                )

            val metricsBefore = metricsSnapshot()
            handler.handle(updateId = 100L, message = message)
            handler.handle(updateId = 101L, message = message)

            metricsBefore.assertDelta(success = 1.0, idempotent = 1.0, failure = 0.0)
            coVerify(exactly = 1) { awardService.schedule(any()) }
            coVerify(exactly = 1) { telegramApiClient.sendMessage(payload.userId, any(), true, message.message_id) }
        }

    @Test
    fun `validation failure is retriable`() =
        runTest {
            val case = giftCase(id = "case-3", priceStars = 300, prizeId = "item-3")
            stubCase(case)
            val payload = PaymentPayload(caseId = case.id, userId = 42, nonce = "nonce", ts = 1L)
            val drawResult = rngResult(payload, resultItemId = null, ppm = 789)
            stubDraw(payload, drawResult)
            stubSendMessage(payload.userId)
            coEvery { awardService.schedule(any()) } returns Unit

            val invalidMessage =
                paymentMessage(
                    chargeId = "charge-retry",
                    payload = payload,
                    totalAmount = case.priceStars,
                    currency = "USD",
                )

            val metricsBefore = metricsSnapshot()
            handler.handle(updateId = 200L, message = invalidMessage)

            metricsBefore.assertDelta(success = 0.0, idempotent = 0.0, failure = 1.0)
            coVerify(exactly = 0) { awardService.schedule(any()) }
            val afterInvalid = metricsSnapshot()

            val validMessage =
                paymentMessage(
                    chargeId = "charge-retry-success",
                    payload = payload,
                    totalAmount = case.priceStars,
                    currency = STARS_CURRENCY_CODE,
                )

            handler.handle(updateId = 201L, message = validMessage)

            afterInvalid.assertDelta(success = 1.0, idempotent = 0.0, failure = 0.0)
            coVerify(exactly = 1) { awardService.schedule(any()) }
        }

    private fun metricsSnapshot(): MetricsSnapshot =
        MetricsSnapshot(
            success = metricCount(MetricsNames.PAY_SUCCESS_TOTAL),
            idempotent = metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL),
            failure = metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL),
        )

    private fun metricCount(name: String): Double =
        Metrics.counter(meterRegistry, name, MetricsTags.COMPONENT to "payments").count()

    private fun giftCase(
        id: String,
        priceStars: Long,
        prizeId: String,
    ): CaseConfig =
        CaseConfig(
            id = id,
            title = "Case $id",
            priceStars = priceStars,
            rtpExtMin = 0.4,
            rtpExtMax = 0.5,
            jackpotAlpha = 0.02,
            items = listOf(PrizeItemConfig(id = prizeId, type = CaseSlotType.GIFT, probabilityPpm = 1_000_000)),
        )

    private fun rngResult(
        payload: PaymentPayload,
        resultItemId: String?,
        ppm: Int,
    ): RngDrawResult {
        val record =
            RngDrawRecord(
                caseId = payload.caseId,
                userId = payload.userId,
                nonce = payload.nonce,
                serverSeedHash = "hash",
                rollHex = "abcdef",
                ppm = ppm,
                resultItemId = resultItemId,
                createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        val receipt =
            RngReceipt(
                date = LocalDate.parse("2024-01-01"),
                serverSeedHash = "hash",
                clientSeed = "client-seed",
                rollHex = "abcdef",
                ppm = ppm,
            )
        return RngDrawResult(record, receipt)
    }

    private fun stubCase(case: CaseConfig) {
        every { casesRepository.get(case.id) } returns case
    }

    private fun stubDraw(
        payload: PaymentPayload,
        result: RngDrawResult,
    ) {
        every { rngService.draw(payload.caseId, payload.userId, payload.nonce) } returns result
    }

    private fun stubSendMessage(chatId: Long) {
        coEvery {
            telegramApiClient.sendMessage(
                chatId = chatId,
                text = any(),
                disableNotification = true,
                replyToMessageId = any(),
            )
        } returns messageStub(chatId)
    }

    private fun paymentMessage(
        chargeId: String,
        payload: PaymentPayload,
        totalAmount: Long,
        currency: String,
    ): MessageDto =
        MessageDto(
            message_id = 1,
            date = 0,
            chat = ChatDto(id = payload.userId, type = "private"),
            successful_payment =
                SuccessfulPaymentDto(
                    currency = currency,
                    total_amount = totalAmount,
                    invoice_payload = payload.encode(),
                    telegram_payment_charge_id = chargeId,
                    provider_payment_charge_id = "provider",
                ),
        )

    private fun messageStub(chatId: Long): MessageDto =
        MessageDto(
            message_id = 10,
            date = 0,
            chat = ChatDto(id = chatId, type = "private"),
        )

    private fun assertPlanMatches(
        plan: AwardPlan,
        payload: PaymentPayload,
        totalAmount: Long,
        drawResult: RngDrawResult,
        chargeId: String,
    ) {
        assertEquals(chargeId, plan.telegramPaymentChargeId)
        assertEquals(payload.caseId, plan.caseId)
        assertEquals(payload.userId, plan.userId)
        assertEquals(payload.nonce, plan.nonce)
        assertEquals(totalAmount, plan.totalAmount)
        assertEquals(STARS_CURRENCY_CODE, plan.currency)
        assertEquals(drawResult.record, plan.rngRecord)
        assertEquals(drawResult.receipt, plan.rngReceipt)
        assertEquals(drawResult.record.resultItemId, plan.resultItemId)
    }

    private data class MetricsSnapshot(
        val success: Double,
        val idempotent: Double,
        val failure: Double,
    )

    private fun MetricsSnapshot.assertDelta(
        success: Double,
        idempotent: Double,
        failure: Double,
    ) {
        assertEquals(this.success + success, metricCount(MetricsNames.PAY_SUCCESS_TOTAL))
        assertEquals(this.idempotent + idempotent, metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL))
        assertEquals(this.failure + failure, metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL))
    }
}
