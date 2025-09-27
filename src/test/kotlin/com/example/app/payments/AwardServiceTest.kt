package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CaseSlotType
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.economy.PrizeItemConfig
import com.example.giftsbot.rng.RngDrawRecord
import com.example.giftsbot.rng.RngReceipt
import com.example.giftsbot.telegram.GiftDto
import com.example.giftsbot.telegram.TelegramApiClient
import com.example.giftsbot.telegram.TelegramApiException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AwardServiceTest {
    @MockK
    private lateinit var telegramApiClient: TelegramApiClient

    @MockK
    private lateinit var casesRepository: CasesRepository

    @MockK
    private lateinit var giftCatalogCache: GiftCatalogCache

    @MockK
    private lateinit var refundService: RefundService

    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var awardService: TelegramAwardService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        meterRegistry = SimpleMeterRegistry()
        awardService =
            TelegramAwardService(
                telegramApiClient = telegramApiClient,
                casesRepository = casesRepository,
                giftCatalogCache = giftCatalogCache,
                refundService = refundService,
                meterRegistry = meterRegistry,
            )
    }

    @Test
    fun `gift prize delivered updates metrics`() =
        runTest {
            val case = caseConfig(CaseSlotType.GIFT, starCost = 500)
            every { casesRepository.get(case.id) } returns case
            val gift = GiftDto(id = "gift-1", starCount = 500)
            coEvery { giftCatalogCache.getGifts() } returns listOf(gift)
            coEvery { telegramApiClient.sendGift(userId = 101, giftId = gift.id, payForUpgrade = false) } returns Unit

            val plan = awardPlan(case.id, prizeId = case.items.first().id, userId = 101)
            val successBefore = metricCount(MetricsNames.AWARD_GIFT_TOTAL)

            awardService.schedule(plan)

            assertEquals(successBefore + 1.0, metricCount(MetricsNames.AWARD_GIFT_TOTAL))
            coVerify(exactly = 1) { telegramApiClient.sendGift(101, gift.id, false) }
            coVerify(exactly = 0) { refundService.refundStarPayment(any(), any(), any()) }
        }

    @Test
    fun `premium prize delivered updates metrics`() =
        runTest {
            val case = caseConfig(CaseSlotType.PREMIUM_3M, starCost = 1_000)
            every { casesRepository.get(case.id) } returns case
            coEvery {
                telegramApiClient.giftPremiumSubscription(
                    userId = 202,
                    monthCount = 3,
                    starCount = 1_000,
                )
            } returns Unit

            val plan = awardPlan(case.id, prizeId = case.items.first().id, userId = 202)
            val premiumBefore = metricCount(MetricsNames.AWARD_PREMIUM_TOTAL)

            awardService.schedule(plan)

            assertEquals(premiumBefore + 1.0, metricCount(MetricsNames.AWARD_PREMIUM_TOTAL))
            coVerify(exactly = 1) {
                telegramApiClient.giftPremiumSubscription(202, monthCount = 3, starCount = 1_000)
            }
            coVerify(exactly = 0) { refundService.refundStarPayment(any(), any(), any()) }
        }

    @Test
    fun `gift delivery failure triggers refund and allows retry`() =
        runTest {
            val case = caseConfig(CaseSlotType.GIFT, starCost = 750)
            every { casesRepository.get(case.id) } returns case
            val gift = GiftDto(id = "gift-2", starCount = 750)
            coEvery { giftCatalogCache.getGifts() } returns listOf(gift)
            val failure = TelegramApiException("503")
            coEvery {
                telegramApiClient.sendGift(
                    userId = 303,
                    giftId = gift.id,
                    payForUpgrade = false,
                )
            } throws failure andThen Unit
            coEvery {
                refundService.refundStarPayment(
                    userId = 303,
                    telegramPaymentChargeId = "charge-303",
                    reason = any(),
                )
            } returns Unit

            val plan = awardPlan(case.id, prizeId = case.items.first().id, userId = 303, chargeId = "charge-303")
            val failBefore = metricCount(MetricsNames.AWARD_FAIL_TOTAL)
            val giftBefore = metricCount(MetricsNames.AWARD_GIFT_TOTAL)

            assertFailsWith<TelegramApiException> { awardService.schedule(plan) }

            assertEquals(failBefore + 1.0, metricCount(MetricsNames.AWARD_FAIL_TOTAL))
            assertEquals(giftBefore, metricCount(MetricsNames.AWARD_GIFT_TOTAL))
            val reasonSlot = slot<RefundReason>()
            coVerify(exactly = 1) {
                refundService.refundStarPayment(
                    userId = 303,
                    telegramPaymentChargeId = "charge-303",
                    reason = capture(reasonSlot),
                )
            }
            val capturedReason = reasonSlot.captured as RefundReason.Award
            assertTrue(capturedReason.detail?.contains("503") == true)

            awardService.schedule(plan)

            assertEquals(giftBefore + 1.0, metricCount(MetricsNames.AWARD_GIFT_TOTAL))
            coVerify(exactly = 2) { telegramApiClient.sendGift(303, gift.id, false) }
        }

    private fun metricCount(name: String): Double =
        Metrics.counter(meterRegistry, name, MetricsTags.COMPONENT to "payments").count()

    private fun caseConfig(
        type: CaseSlotType,
        starCost: Long,
    ): CaseConfig =
        CaseConfig(
            id = "case-${type.name.lowercase()}",
            title = "Case",
            priceStars = starCost,
            rtpExtMin = 0.4,
            rtpExtMax = 0.5,
            jackpotAlpha = 0.01,
            items =
                listOf(
                    PrizeItemConfig(
                        id = "prize-${type.name}",
                        type = type,
                        starCost = starCost,
                        probabilityPpm = 1_000_000,
                    ),
                ),
        )

    private fun awardPlan(
        caseId: String,
        prizeId: String?,
        userId: Long,
        chargeId: String = "charge-$userId",
    ): AwardPlan =
        AwardPlan(
            telegramPaymentChargeId = chargeId,
            providerPaymentChargeId = "provider",
            totalAmount = 1_000,
            currency = STARS_CURRENCY_CODE,
            userId = userId,
            caseId = caseId,
            nonce = "nonce",
            resultItemId = prizeId,
            rngRecord =
                RngDrawRecord(
                    caseId = caseId,
                    userId = userId,
                    nonce = "nonce",
                    serverSeedHash = "hash",
                    rollHex = "abcd",
                    ppm = 123,
                    resultItemId = prizeId,
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            rngReceipt =
                RngReceipt(
                    date = LocalDate.parse("2024-01-01"),
                    serverSeedHash = "hash",
                    clientSeed = "client",
                    rollHex = "abcd",
                    ppm = 123,
                ),
        )
}
