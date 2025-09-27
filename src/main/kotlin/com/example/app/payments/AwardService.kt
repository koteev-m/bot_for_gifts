package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.giftsbot.economy.CaseSlotType
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.economy.PrizeItemConfig
import com.example.giftsbot.telegram.GiftDto
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

interface AwardService {
    suspend fun schedule(plan: AwardPlan)
}

internal class TelegramAwardService(
    private val telegramApiClient: TelegramApiClient,
    private val casesRepository: CasesRepository,
    private val giftCatalogCache: GiftCatalogCache,
    private val refundService: RefundService,
    meterRegistry: MeterRegistry,
) : AwardService {
    private val logger = LoggerFactory.getLogger(TelegramAwardService::class.java)
    private val journal = ConcurrentHashMap<String, AwardJournalEntry>()

    private val giftCounter =
        Metrics.counter(meterRegistry, MetricsNames.AWARD_GIFT_TOTAL, MetricsTags.COMPONENT to COMPONENT_VALUE)
    private val premiumCounter =
        Metrics.counter(meterRegistry, MetricsNames.AWARD_PREMIUM_TOTAL, MetricsTags.COMPONENT to COMPONENT_VALUE)
    private val internalCounter =
        Metrics.counter(meterRegistry, MetricsNames.AWARD_INTERNAL_TOTAL, MetricsTags.COMPONENT to COMPONENT_VALUE)
    private val failCounter =
        Metrics.counter(meterRegistry, MetricsNames.AWARD_FAIL_TOTAL, MetricsTags.COMPONENT to COMPONENT_VALUE)

    override suspend fun schedule(plan: AwardPlan) {
        val chargeId = plan.telegramPaymentChargeId
        val previous = journal.putIfAbsent(chargeId, AwardJournalEntry.InProgress)
        if (previous != null) {
            logDuplicate(plan, previous)
            return
        }
        runAwardFlow(plan)
    }

    private suspend fun runAwardFlow(plan: AwardPlan) {
        val chargeId = plan.telegramPaymentChargeId
        val outcome = runCatching { deliverPrize(plan) }
        outcome.onSuccess { journal[chargeId] = it }
        outcome.onFailure { cause -> handleDeliveryFailure(plan, chargeId, cause) }
    }

    private suspend fun handleDeliveryFailure(
        plan: AwardPlan,
        chargeId: String,
        cause: Throwable,
    ) {
        if (cause is CancellationException) {
            journal.remove(chargeId, AwardJournalEntry.InProgress)
            throw cause
        }
        journal.remove(chargeId, AwardJournalEntry.InProgress)
        failCounter.increment()
        refundIfPossible(plan, cause)
        logger.error(
            "award delivery failed: chargeId={} userId={} caseId={} prizeId={} reason={}",
            chargeId,
            plan.userId,
            plan.caseId,
            plan.resultItemId,
            cause.message,
            cause,
        )
        throw cause
    }

    private suspend fun deliverPrize(plan: AwardPlan): AwardJournalEntry.Completed {
        val prize = resolvePrize(plan)
        return when (prize?.type) {
            CaseSlotType.GIFT -> deliverGift(plan, prize)
            CaseSlotType.PREMIUM_3M ->
                deliverPremium(plan, prize.id, monthCount = 3, starCount = PREMIUM_3_MONTHS_STARS)
            CaseSlotType.PREMIUM_6M ->
                deliverPremium(plan, prize.id, monthCount = 6, starCount = PREMIUM_6_MONTHS_STARS)
            CaseSlotType.PREMIUM_12M ->
                deliverPremium(plan, prize.id, monthCount = 12, starCount = PREMIUM_12_MONTHS_STARS)
            CaseSlotType.INTERNAL, null -> deliverInternal(plan, prize?.id)
        }
    }

    private suspend fun deliverGift(
        plan: AwardPlan,
        prize: PrizeItemConfig,
    ): AwardJournalEntry.Completed {
        val starCost = prize.starCost ?: throw AwardDeliveryException("gift_star_cost_missing prizeId=${prize.id}")
        val gifts = giftCatalogCache.getGifts()
        val gift =
            selectGift(gifts, starCost, logger)
                ?: throw AwardDeliveryException(
                    "gift_not_found caseId=${plan.caseId} prizeId=${prize.id} starCost=$starCost",
                )
        telegramApiClient.sendGift(userId = plan.userId, giftId = gift.id, payForUpgrade = false)
        giftCounter.increment()
        logger.info(
            "award gift delivered: chargeId={} userId={} caseId={} prizeId={} giftId={} starCost={}",
            plan.telegramPaymentChargeId,
            plan.userId,
            plan.caseId,
            prize.id,
            gift.id,
            starCost,
        )
        return AwardJournalEntry.Completed(AwardKind.GIFT, prize.id, gift.id)
    }

    private suspend fun deliverPremium(
        plan: AwardPlan,
        prizeId: String,
        monthCount: Int,
        starCount: Long,
    ): AwardJournalEntry.Completed {
        if (starCount !in VALID_PREMIUM_STAR_COUNTS) {
            throw AwardDeliveryException("invalid_premium_star_count starCount=$starCount monthCount=$monthCount")
        }
        telegramApiClient.giftPremiumSubscription(
            userId = plan.userId,
            monthCount = monthCount,
            starCount = starCount,
        )
        premiumCounter.increment()
        logger.info(
            "award premium delivered: chargeId={} userId={} caseId={} prizeId={} monthCount={} starCount={}",
            plan.telegramPaymentChargeId,
            plan.userId,
            plan.caseId,
            prizeId,
            monthCount,
            starCount,
        )
        return AwardJournalEntry.Completed(AwardKind.PREMIUM, prizeId, null)
    }

    private fun deliverInternal(
        plan: AwardPlan,
        prizeId: String?,
    ): AwardJournalEntry.Completed {
        internalCounter.increment()
        logger.info(
            "award internal recorded: chargeId={} userId={} caseId={} prizeId={}",
            plan.telegramPaymentChargeId,
            plan.userId,
            plan.caseId,
            prizeId ?: "-",
        )
        return AwardJournalEntry.Completed(AwardKind.INTERNAL, prizeId, null)
    }

    private fun resolvePrize(plan: AwardPlan): PrizeItemConfig? {
        val case =
            casesRepository.get(plan.caseId)
                ?: throw AwardDeliveryException("case_not_found caseId=${plan.caseId}")
        val prizeId = plan.resultItemId ?: return null
        return case.items.firstOrNull { it.id == prizeId }
            ?: throw AwardDeliveryException("prize_not_found caseId=${plan.caseId} prizeId=$prizeId")
    }

    private fun logDuplicate(
        plan: AwardPlan,
        entry: AwardJournalEntry,
    ) {
        val stateDescription =
            when (entry) {
                AwardJournalEntry.InProgress -> "in_progress"
                is AwardJournalEntry.Completed ->
                    buildString {
                        append(entry.kind.name.lowercase())
                        entry.prizeId?.let { append(" prizeId=").append(it) }
                        entry.externalId?.let { append(" externalId=").append(it) }
                    }
            }
        logger.info(
            "award duplicate ignored: chargeId={} userId={} caseId={} state={}",
            plan.telegramPaymentChargeId,
            plan.userId,
            plan.caseId,
            stateDescription,
        )
    }

    private suspend fun refundIfPossible(
        plan: AwardPlan,
        cause: Throwable,
    ) {
        if (!plan.currency.equals(STARS_CURRENCY_CODE, ignoreCase = true)) {
            logger.warn(
                "award refund skipped: chargeId={} userId={} currency={} reason={}",
                plan.telegramPaymentChargeId,
                plan.userId,
                plan.currency,
                cause.javaClass.simpleName,
            )
            return
        }
        val detail = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        runCatching {
            refundService.refundStarPayment(
                userId = plan.userId,
                telegramPaymentChargeId = plan.telegramPaymentChargeId,
                reason = RefundReason.Award(detail),
            )
        }.onFailure { refundError ->
            logger.error(
                "award refund failed: chargeId={} userId={} detail={}",
                plan.telegramPaymentChargeId,
                plan.userId,
                detail,
                refundError,
            )
        }
    }

    companion object {
        private const val COMPONENT_VALUE = "payments"
        private const val PREMIUM_3_MONTHS_STARS = 1_000L
        private const val PREMIUM_6_MONTHS_STARS = 1_500L
        private const val PREMIUM_12_MONTHS_STARS = 2_500L
        private val VALID_PREMIUM_STAR_COUNTS =
            setOf(PREMIUM_3_MONTHS_STARS, PREMIUM_6_MONTHS_STARS, PREMIUM_12_MONTHS_STARS)
    }
}

internal class AwardDeliveryException(
    message: String,
) : RuntimeException(message)

private sealed interface AwardJournalEntry {
    data class Completed(
        val kind: AwardKind,
        val prizeId: String?,
        val externalId: String?,
    ) : AwardJournalEntry

    data object InProgress : AwardJournalEntry
}

private enum class AwardKind {
    GIFT,
    PREMIUM,
    INTERNAL,
}

private fun selectGift(
    gifts: List<GiftDto>,
    starCost: Long,
    logger: org.slf4j.Logger,
): GiftDto? {
    val candidates = gifts.filter { it.starCount == starCost }
    if (candidates.isEmpty()) {
        return null
    }
    if (candidates.size > 1) {
        logger.warn("multiple gifts matched starCost={} count={} choosing first", starCost, candidates.size)
    }
    return candidates.first()
}
