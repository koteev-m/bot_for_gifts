package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.payments.AwardPlan
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
import com.example.giftsbot.telegram.CreateInvoiceLinkRequest
import com.example.giftsbot.telegram.MessageDto
import com.example.giftsbot.telegram.PreCheckoutQueryDto
import com.example.giftsbot.telegram.SuccessfulPaymentDto
import com.example.giftsbot.telegram.TelegramApiClient
import com.example.giftsbot.telegram.UserDto
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

class PaymentsFlowTest {
    @MockK
    private lateinit var telegramApiClient: TelegramApiClient

    @MockK
    private lateinit var casesRepository: CasesRepository

    @MockK
    private lateinit var rngService: RngService

    @MockK
    private lateinit var awardService: AwardService

    @MockK
    private lateinit var refundService: RefundService

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var invoiceService: TelegramInvoiceService
    private lateinit var preCheckoutHandler: PreCheckoutHandler
    private lateinit var successfulPaymentHandler: SuccessfulPaymentHandler

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        meterRegistry = SimpleMeterRegistry()
        val paymentsConfig =
            PaymentsConfig(
                currency = STARS_CURRENCY_CODE,
                titlePrefix = "Mega",
                receiptEnabled = true,
                businessConnectionId = null,
            )
        invoiceService = TelegramInvoiceService(casesRepository, telegramApiClient, paymentsConfig)
        preCheckoutHandler = PreCheckoutHandler(telegramApiClient, casesRepository, meterRegistry)
        successfulPaymentHandler =
            SuccessfulPaymentHandler(
                telegramApiClient = telegramApiClient,
                rngService = rngService,
                casesRepository = casesRepository,
                awardService = awardService,
                paymentSupport = PaymentSupport(config = paymentsConfig, refundService = refundService),
                meterRegistry = meterRegistry,
            )
    }

    @Test
    fun `invoice creation and pre checkout approve`() =
        runTest {
            val case = defaultCase()
            every { casesRepository.get(case.id) } returns case
            val requestSlot = slot<CreateInvoiceLinkRequest>()
            coEvery { telegramApiClient.createInvoiceLink(capture(requestSlot)) } returns "https://t.me/invoice/abc"
            coEvery { telegramApiClient.answerPreCheckoutQuery(any(), any(), any()) } returns true

            val response = invoiceService.createCaseInvoice(case.id, userId = 42, nonce = "nonce-1")
            assertEquals("https://t.me/invoice/abc", response.invoiceLink)
            assertEquals(response.payload, requestSlot.captured.payload)
            assertEquals(
                case.priceStars,
                requestSlot.captured.prices
                    .single()
                    .amount,
            )

            val query =
                PreCheckoutQueryDto(
                    id = "query-1",
                    from = user(42),
                    currency = STARS_CURRENCY_CODE,
                    total_amount = case.priceStars,
                    invoice_payload = response.payload,
                )

            val okBefore = metricCount(MetricsNames.PRE_CHECKOUT_TOTAL, MetricsTags.RESULT to "ok")
            val failBefore = metricCount(MetricsNames.PRE_CHECKOUT_TOTAL, MetricsTags.RESULT to "fail")

            preCheckoutHandler.handle(updateId = 100, query = query)

            coVerify(exactly = 1) { telegramApiClient.answerPreCheckoutQuery(query.id, true, null) }
            assertEquals(okBefore + 1.0, metricCount(MetricsNames.PRE_CHECKOUT_TOTAL, MetricsTags.RESULT to "ok"))
            assertEquals(failBefore, metricCount(MetricsNames.PRE_CHECKOUT_TOTAL, MetricsTags.RESULT to "fail"))
        }

    @Test
    fun `successful payment awards once and is idempotent`() =
        runTest {
            val case = defaultCase()
            every { casesRepository.get(case.id) } returns case
            val prizeId = case.items.first().id
            val payload = PaymentPayload(caseId = case.id, userId = 900, nonce = "nonce-2", ts = 1L)
            val drawRecord = rngRecord(case.id, payload.userId, payload.nonce, prizeId)
            val receipt = rngReceipt()
            every { rngService.draw(case.id, payload.userId, payload.nonce) } returns RngDrawResult(drawRecord, receipt)
            val planSlot = slot<AwardPlan>()
            coEvery { awardService.schedule(capture(planSlot)) } returns Unit
            coEvery {
                telegramApiClient.sendMessage(
                    chatId = payload.userId,
                    text = any(),
                    disableNotification = true,
                    replyToMessageId = any(),
                )
            } returns receiptMessage(payload.userId)

            val message = paymentMessage("charge-1", payload.encode(), payload.userId, case.priceStars)

            val successBefore = metricCount(MetricsNames.PAY_SUCCESS_TOTAL)
            val idempotentBefore = metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL)
            val failBefore = metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL)

            successfulPaymentHandler.handle(updateId = 200, message = message)
            successfulPaymentHandler.handle(updateId = 201, message = message)

            assertEquals(successBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_TOTAL))
            assertEquals(idempotentBefore + 1.0, metricCount(MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL))
            assertEquals(failBefore, metricCount(MetricsNames.PAY_SUCCESS_FAIL_TOTAL))
            coVerify(exactly = 1) { awardService.schedule(any()) }
            val capturedPlan = planSlot.captured
            assertNotNull(capturedPlan)
            assertEquals("charge-1", capturedPlan.telegramPaymentChargeId)
            assertEquals(payload.userId, capturedPlan.userId)
            assertEquals(payload.caseId, capturedPlan.caseId)
            assertEquals(payload.nonce, capturedPlan.nonce)
            assertEquals(case.priceStars, capturedPlan.totalAmount)
            assertEquals(STARS_CURRENCY_CODE, capturedPlan.currency)
            assertEquals(drawRecord, capturedPlan.rngRecord)
            assertEquals(receipt, capturedPlan.rngReceipt)
        }

    private fun metricCount(
        name: String,
        vararg tags: Pair<String, String>,
    ): Double = Metrics.counter(meterRegistry, name, MetricsTags.COMPONENT to "payments", *tags).count()

    private fun defaultCase(): CaseConfig =
        CaseConfig(
            id = "case",
            title = "Case",
            priceStars = 700,
            rtpExtMin = 0.4,
            rtpExtMax = 0.6,
            jackpotAlpha = 0.01,
            items =
                listOf(
                    PrizeItemConfig(
                        id = "prize",
                        type = CaseSlotType.GIFT,
                        starCost = 700,
                        probabilityPpm = 1_000_000,
                    ),
                ),
        )

    private fun user(id: Long): UserDto = UserDto(id = id, is_bot = false, first_name = "User")

    private fun paymentMessage(
        chargeId: String,
        payload: String,
        userId: Long,
        totalAmount: Long,
    ): MessageDto =
        MessageDto(
            message_id = 1,
            date = 0,
            chat = ChatDto(id = userId, type = "private"),
            successful_payment =
                SuccessfulPaymentDto(
                    currency = STARS_CURRENCY_CODE,
                    total_amount = totalAmount,
                    invoice_payload = payload,
                    telegram_payment_charge_id = chargeId,
                    provider_payment_charge_id = "provider",
                ),
        )

    private fun rngRecord(
        caseId: String,
        userId: Long,
        nonce: String,
        prizeId: String,
    ): RngDrawRecord =
        RngDrawRecord(
            caseId = caseId,
            userId = userId,
            nonce = nonce,
            serverSeedHash = "hash",
            rollHex = "abcd",
            ppm = 123,
            resultItemId = prizeId,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

    private fun rngReceipt(): RngReceipt =
        RngReceipt(
            date = LocalDate.parse("2024-01-01"),
            serverSeedHash = "hash",
            clientSeed = "client",
            rollHex = "abcd",
            ppm = 123,
        )

    private fun receiptMessage(chatId: Long): MessageDto =
        MessageDto(message_id = 10, date = 0, chat = ChatDto(id = chatId, type = "private"))
}
