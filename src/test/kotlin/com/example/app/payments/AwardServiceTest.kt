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
import com.example.giftsbot.telegram.AvailableGiftsDto
import com.example.giftsbot.telegram.GiftDto
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AwardServiceTest {
    @MockK
    private lateinit var api: TelegramApiClient

    @MockK
    private lateinit var casesRepository: CasesRepository

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var giftCatalogCache: GiftCatalogCache
    private lateinit var service: AwardService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        meterRegistry = SimpleMeterRegistry()
        giftCatalogCache = GiftCatalogCache(api, Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC))
        service = TelegramAwardService(api, casesRepository, giftCatalogCache, meterRegistry)
    }

    @Test
    fun `gift prize is delivered via sendGift`() = runTest {
        val prize = PrizeItemConfig(id = "gift-item", type = CaseSlotType.GIFT, starCost = 49, probabilityPpm = 1_000_000)
        stubCase("case-gift", prize)
        coEvery { api.getAvailableGifts() } returns AvailableGiftsDto(listOf(GiftDto(id = "123", starCount = 49)))
        coEvery { api.sendGift(userId = 77, giftId = "123", payForUpgrade = false) } returns Unit

        val plan = plan(chargeId = "gift-charge", caseId = "case-gift", prizeId = prize.id, userId = 77)
        val before = metricCount(MetricsNames.AWARD_GIFT_TOTAL)

        service.schedule(plan)

        assertEquals(before + 1.0, metricCount(MetricsNames.AWARD_GIFT_TOTAL))
        coVerify(exactly = 1) { api.sendGift(userId = 77, giftId = "123", payForUpgrade = false) }
        coVerify(exactly = 1) { api.getAvailableGifts() }

        // duplicate should be idempotent
        service.schedule(plan)
        coVerify(exactly = 1) { api.sendGift(userId = 77, giftId = "123", payForUpgrade = false) }
    }

    @Test
    fun `premium prize uses expected star count`() = runTest {
        val prize = PrizeItemConfig(id = "premium-3m", type = CaseSlotType.PREMIUM_3M, probabilityPpm = 1_000_000)
        stubCase("case-premium", prize)
        coEvery { api.giftPremiumSubscription(userId = 88, monthCount = 3, starCount = 1_000) } returns Unit

        val plan = plan(chargeId = "premium-charge", caseId = "case-premium", prizeId = prize.id, userId = 88)
        val before = metricCount(MetricsNames.AWARD_PREMIUM_TOTAL)

        service.schedule(plan)

        assertEquals(before + 1.0, metricCount(MetricsNames.AWARD_PREMIUM_TOTAL))
        coVerify(exactly = 1) { api.giftPremiumSubscription(userId = 88, monthCount = 3, starCount = 1_000) }
    }

    @Test
    fun `internal prize increments metric without external calls`() = runTest {
        val prize = PrizeItemConfig(id = "internal", type = CaseSlotType.INTERNAL, probabilityPpm = 1_000_000)
        stubCase("case-internal", prize)

        val plan = plan(chargeId = "internal-charge", caseId = "case-internal", prizeId = prize.id, userId = 99)
        val before = metricCount(MetricsNames.AWARD_INTERNAL_TOTAL)

        service.schedule(plan)

        assertEquals(before + 1.0, metricCount(MetricsNames.AWARD_INTERNAL_TOTAL))
        coVerify(exactly = 0) { api.sendGift(any(), any(), any()) }
        coVerify(exactly = 0) { api.giftPremiumSubscription(any(), any(), any()) }
    }

    @Test
    fun `missing gift mapping increments fail metric`() = runTest {
        val prize = PrizeItemConfig(id = "gift-missing", type = CaseSlotType.GIFT, starCost = 77, probabilityPpm = 1_000_000)
        stubCase("case-failure", prize)
        coEvery { api.getAvailableGifts() } returns AvailableGiftsDto(listOf(GiftDto(id = "456", starCount = 50)))

        val plan = plan(chargeId = "fail-charge", caseId = "case-failure", prizeId = prize.id, userId = 55)
        val before = metricCount(MetricsNames.AWARD_FAIL_TOTAL)

        assertFailsWith<AwardDeliveryException> { service.schedule(plan) }

        assertEquals(before + 1.0, metricCount(MetricsNames.AWARD_FAIL_TOTAL))
        coVerify(exactly = 0) { api.sendGift(any(), any(), any()) }
    }

    private fun stubCase(caseId: String, prize: PrizeItemConfig) {
        val case =
            CaseConfig(
                id = caseId,
                title = caseId,
                priceStars = 100,
                rtpExtMin = 0.4,
                rtpExtMax = 0.6,
                jackpotAlpha = 0.02,
                items = listOf(prize),
            )
        every { casesRepository.get(caseId) } returns case
    }

    private fun plan(
        chargeId: String,
        caseId: String,
        prizeId: String?,
        userId: Long,
    ): AwardPlan =
        AwardPlan(
            telegramPaymentChargeId = chargeId,
            providerPaymentChargeId = "provider",
            totalAmount = 100,
            currency = "XTR",
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
                    rollHex = "deadbeef",
                    ppm = 123,
                    resultItemId = prizeId,
                    createdAt = NOW,
                ),
            rngReceipt =
                RngReceipt(
                    date = LocalDate.parse("2024-01-01"),
                    serverSeedHash = "hash",
                    clientSeed = "client",
                    rollHex = "deadbeef",
                    ppm = 123,
                ),
        )

    private fun metricCount(name: String): Double =
        Metrics.counter(meterRegistry, name, MetricsTags.COMPONENT to COMPONENT_VALUE).count()

    companion object {
        private val NOW: Instant = Instant.parse("2024-01-01T00:00:00Z")
        private const val COMPONENT_VALUE = "payments"
    }
}
