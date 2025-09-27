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
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.every
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

    private lateinit var meterRegistry: MeterRegistry

    private lateinit var handler: SuccessfulPaymentHandler

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        meterRegistry = SimpleMeterRegistry()
        val paymentsConfig = PaymentsConfig(currency = STARS_CURRENCY_CODE, titlePrefix = null, receiptEnabled = true, businessConnectionId = null)
        handler = SuccessfulPaymentHandler(
            telegramApiClient = telegramApiClient,
            rngService = rngService,
            casesRepository = casesRepository,
            awardService = awardService,
            paymentsConfig = paymentsConfig,
            meterRegistry = meterRegistry,
        )
    }

    @Test
    fun `handle successful payment builds plan and sends receipt`() = runTest {
        val case = CaseConfig(
            id = "case-1",
            title = "Case 1",
            priceStars = 100,
            rtpExtMin = 0.4,
            rtpExtMax = 0.5,
            jackpotAlpha = 0.02,
            items = listOf(PrizeItemConfig(id = "item-1", type = CaseSlotType.GIFT, probabilityPpm = 1_000_000)),
        )
        val planSlot = slot<AwardPlan>()
        coEvery { awardService.schedule(capture(planSlot)) } returns Unit
        every { casesRepository.get(case.id) } returns case
        val payload = PaymentPayload(caseId = case.id, userId = 777, nonce = "nonce", ts = 1L)
        val drawRecord =
            RngDrawRecord(
                caseId = case.id,
                userId = payload.userId,
                nonce = payload.nonce,
                serverSeedHash = "hash",
                rollHex = "abcdef",
                ppm = 123,
                resultItemId = "item-1",
                createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        val receipt =
            RngReceipt(
                date = LocalDate.parse("2024-01-01"),
                serverSeedHash = "hash",
                clientSeed = "client-seed",
                rollHex = "abcdef",
                ppm = 123,
            )
        every { rngService.draw(case.id, payload.userId, payload.nonce) } returns RngDrawResult(drawRecord, receipt)
        coEvery {
            telegramApiClient.sendMessage(
                chatId = payload.userId,
                text = any(),
                disableNotification = true,
                replyToMessageId = any(),
            )
        } returns messageStub(chatId = payload.userId)

        val message = successfulPaymentMessage(
            chargeId = "charge-1",
            payload = payload.encode(),
            userId = payload.userId,
            totalAmount = case.priceStars,
            currency = STARS_CURRENCY_CODE,
        )

        val successBefore = metricCount(MetricsNames.PAY_SUCCESS_TOTAL)
        val idempotentBefore = metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL)
        val failBefore = metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL)
        handler.handle(updateId = 1L, message = message)

        val capturedPlan = planSlot.captured
        assertNotNull(capturedPlan)
        assertEquals("charge-1", capturedPlan.telegramPaymentChargeId)
        assertEquals(case.id, capturedPlan.caseId)
        assertEquals(payload.userId, capturedPlan.userId)
        assertEquals(payload.nonce, capturedPlan.nonce)
        assertEquals(case.priceStars, capturedPlan.totalAmount)
        assertEquals(STARS_CURRENCY_CODE, capturedPlan.currency)
        assertEquals(drawRecord, capturedPlan.rngRecord)
        assertEquals(receipt, capturedPlan.rngReceipt)
        assertEquals("item-1", capturedPlan.resultItemId)

        assertEquals(successBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_TOTAL))
        assertEquals(idempotentBefore, metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL))
        assertEquals(failBefore, metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL))

        coVerify(exactly = 1) { awardService.schedule(any()) }
        coVerify(exactly = 1) { telegramApiClient.sendMessage(payload.userId, any(), true, message.message_id) }
    }

    @Test
    fun `duplicate payment is idempotent`() = runTest {
        val case = CaseConfig(
            id = "case-2",
            title = "Case 2",
            priceStars = 200,
            rtpExtMin = 0.4,
            rtpExtMax = 0.5,
            jackpotAlpha = 0.02,
            items = listOf(PrizeItemConfig(id = "item-2", type = CaseSlotType.GIFT, probabilityPpm = 1_000_000)),
        )
        coEvery { awardService.schedule(any()) } returns Unit
        every { casesRepository.get(case.id) } returns case
        val payload = PaymentPayload(caseId = case.id, userId = 900, nonce = "nonce", ts = 1L)
        val drawRecord =
            RngDrawRecord(
                caseId = case.id,
                userId = payload.userId,
                nonce = payload.nonce,
                serverSeedHash = "hash",
                rollHex = "abcdef",
                ppm = 456,
                resultItemId = "item-2",
                createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        val receipt =
            RngReceipt(
                date = LocalDate.parse("2024-01-02"),
                serverSeedHash = "hash",
                clientSeed = "seed",
                rollHex = "abcdef",
                ppm = 456,
            )
        every { rngService.draw(case.id, payload.userId, payload.nonce) } returns RngDrawResult(drawRecord, receipt)
        coEvery {
            telegramApiClient.sendMessage(payload.userId, any(), true, any())
        } returns messageStub(chatId = payload.userId)

        val message = successfulPaymentMessage(
            chargeId = "charge-dup",
            payload = payload.encode(),
            userId = payload.userId,
            totalAmount = case.priceStars,
            currency = STARS_CURRENCY_CODE,
        )

        val successBefore = metricCount(MetricsNames.PAY_SUCCESS_TOTAL)
        val idempotentBefore = metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL)
        val failBefore = metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL)
        handler.handle(updateId = 100L, message = message)
        handler.handle(updateId = 101L, message = message)

        assertEquals(successBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_TOTAL))
        assertEquals(idempotentBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL))
        assertEquals(failBefore, metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL))

        coVerify(exactly = 1) { awardService.schedule(any()) }
        coVerify(exactly = 1) { telegramApiClient.sendMessage(payload.userId, any(), true, message.message_id) }
    }

    @Test
    fun `validation failure is retriable`() = runTest {
        val case = CaseConfig(
            id = "case-3",
            title = "Case 3",
            priceStars = 300,
            rtpExtMin = 0.4,
            rtpExtMax = 0.5,
            jackpotAlpha = 0.02,
            items = listOf(PrizeItemConfig(id = "item-3", type = CaseSlotType.GIFT, probabilityPpm = 1_000_000)),
        )
        every { casesRepository.get(case.id) } returns case
        coEvery { awardService.schedule(any()) } returns Unit
        val payload = PaymentPayload(caseId = case.id, userId = 42, nonce = "nonce", ts = 1L)
        val drawRecord =
            RngDrawRecord(
                caseId = case.id,
                userId = payload.userId,
                nonce = payload.nonce,
                serverSeedHash = "hash",
                rollHex = "abc123",
                ppm = 789,
                resultItemId = null,
                createdAt = Instant.parse("2024-01-03T00:00:00Z"),
            )
        val receipt =
            RngReceipt(
                date = LocalDate.parse("2024-01-03"),
                serverSeedHash = "hash",
                clientSeed = "seed",
                rollHex = "abc123",
                ppm = 789,
            )
        every { rngService.draw(case.id, payload.userId, payload.nonce) } returns RngDrawResult(drawRecord, receipt)
        coEvery { telegramApiClient.sendMessage(payload.userId, any(), true, any()) } returns messageStub(chatId = payload.userId)

        val invalidMessage = successfulPaymentMessage(
            chargeId = "charge-retry",
            payload = payload.encode(),
            userId = payload.userId,
            totalAmount = case.priceStars,
            currency = "USD",
        )
        val successBefore = metricCount(MetricsNames.PAY_SUCCESS_TOTAL)
        val idempotentBefore = metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL)
        val failBefore = metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL)
        handler.handle(updateId = 200L, message = invalidMessage)

        assertEquals(successBefore, metricCount(MetricsNames.PAY_SUCCESS_TOTAL))
        assertEquals(idempotentBefore, metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL))
        assertEquals(failBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL))
        coVerify(exactly = 0) { awardService.schedule(any()) }

        val validMessage = successfulPaymentMessage(
            chargeId = "charge-retry",
            payload = payload.encode(),
            userId = payload.userId,
            totalAmount = case.priceStars,
            currency = STARS_CURRENCY_CODE,
        )
        handler.handle(updateId = 201L, message = validMessage)

        assertEquals(successBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_TOTAL))
        assertEquals(idempotentBefore, metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL))
        assertEquals(failBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL))
        coVerify(exactly = 1) { awardService.schedule(any()) }
    }

    private fun metricCount(name: String): Double =
        Metrics.counter(meterRegistry, name, MetricsTags.COMPONENT to "payments").count()

    private fun successfulPaymentMessage(
        chargeId: String,
        payload: String,
        userId: Long,
        totalAmount: Long,
        currency: String,
    ): MessageDto =
        MessageDto(
            message_id = 1,
            date = 0,
            chat = ChatDto(id = userId, type = "private"),
            successful_payment =
                SuccessfulPaymentDto(
                    currency = currency,
                    total_amount = totalAmount,
                    invoice_payload = payload,
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

}
