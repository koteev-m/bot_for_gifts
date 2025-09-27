package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val REFUND_COMPONENT = "payments"
private val COMPONENT_TAG = MetricsTags.COMPONENT to REFUND_COMPONENT
private val REFUND_SLA = Duration.ofSeconds(2)

class RefundService(
    private val telegramApiClient: TelegramApiClient,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(RefundService::class.java)
    private val journal = ConcurrentHashMap<String, RefundJournalEntry>()
    private val refundCounter =
        Metrics.counter(meterRegistry, MetricsNames.REFUND_TOTAL, COMPONENT_TAG)
    private val refundFailCounter =
        Metrics.counter(meterRegistry, MetricsNames.REFUND_FAIL_TOTAL, COMPONENT_TAG)

    suspend fun refundStarPayment(
        userId: Long,
        telegramPaymentChargeId: String,
        reason: RefundReason,
    ) {
        val normalizedChargeId = telegramPaymentChargeId.trim()
        require(userId > 0) { "userId must be positive" }
        require(normalizedChargeId.isNotEmpty()) { "telegramPaymentChargeId must not be blank" }

        val startDecision = beginAttempt(normalizedChargeId, reason)
        when (startDecision) {
            is BeginResult.Skip -> {
                logDuplicate(userId, normalizedChargeId, startDecision.previous, reason)
                return
            }
            is BeginResult.Start -> {
                executeRefund(userId, normalizedChargeId, reason, startDecision.entry.attempt)
            }
        }
    }

    private fun beginAttempt(
        chargeId: String,
        reason: RefundReason,
    ): BeginResult {
        val startedAt = clock.instant()
        var previous: RefundJournalEntry? = null
        val entry =
            journal.compute(chargeId) { _, existing ->
                previous = existing
                when (existing) {
                    null -> RefundJournalEntry.InProgress(reason, startedAt, attempt = 1)
                    is RefundJournalEntry.Failed ->
                        RefundJournalEntry.InProgress(reason, startedAt, attempt = existing.attempt + 1)
                    else -> existing
                }
            }
        return if (entry is RefundJournalEntry.InProgress) {
            BeginResult.Start(entry)
        } else {
            BeginResult.Skip(previous ?: entry)
        }
    }

    private suspend fun executeRefund(
        userId: Long,
        chargeId: String,
        reason: RefundReason,
        attempt: Int,
    ) {
        val startedAt = clock.instant()
        runCatching { telegramApiClient.refundStarPayment(userId, chargeId) }
            .onSuccess {
                val duration = Duration.between(startedAt, clock.instant())
                finishSuccess(chargeId, reason, attempt, duration)
                logSuccess(userId, chargeId, reason, attempt, duration)
            }.onFailure { cause ->
                finishFailure(chargeId, reason, attempt, cause)
                logFailure(userId, chargeId, reason, attempt, cause)
                throw cause
            }
    }

    private fun finishSuccess(
        chargeId: String,
        reason: RefundReason,
        attempt: Int,
        duration: Duration,
    ) {
        refundCounter.increment()
        journal[chargeId] = RefundJournalEntry.Succeeded(reason, attempt, duration, clock.instant())
    }

    private fun finishFailure(
        chargeId: String,
        reason: RefundReason,
        attempt: Int,
        cause: Throwable,
    ) {
        refundFailCounter.increment()
        journal[chargeId] = RefundJournalEntry.Failed(reason, attempt, cause.message)
    }

    private fun logDuplicate(
        userId: Long,
        chargeId: String,
        existing: RefundJournalEntry?,
        newReason: RefundReason,
    ) {
        val state = existing?.status() ?: "none"
        if (newReason.detail != null) {
            logger.info(
                "refund skipped: userId={} chargeId={} reason={} state={} detail={}",
                userId,
                chargeId,
                newReason.code,
                state,
                newReason.detail,
            )
        } else {
            logger.info(
                "refund skipped: userId={} chargeId={} reason={} state={}",
                userId,
                chargeId,
                newReason.code,
                state,
            )
        }
    }

    private fun logSuccess(
        userId: Long,
        chargeId: String,
        reason: RefundReason,
        attempt: Int,
        duration: Duration,
    ) {
        val durationMs = duration.toMillis()
        if (duration > REFUND_SLA) {
            if (reason.detail != null) {
                logger.warn(
                    "refund slow: userId={} chargeId={} reason={} attempt={} durationMs={} detail={}",
                    userId,
                    chargeId,
                    reason.code,
                    attempt,
                    durationMs,
                    reason.detail,
                )
            } else {
                logger.warn(
                    "refund slow: userId={} chargeId={} reason={} attempt={} durationMs={}",
                    userId,
                    chargeId,
                    reason.code,
                    attempt,
                    durationMs,
                )
            }
            return
        }

        if (reason.detail != null) {
            logger.info(
                "refund completed: userId={} chargeId={} reason={} attempt={} durationMs={} detail={}",
                userId,
                chargeId,
                reason.code,
                attempt,
                durationMs,
                reason.detail,
            )
        } else {
            logger.info(
                "refund completed: userId={} chargeId={} reason={} attempt={} durationMs={}",
                userId,
                chargeId,
                reason.code,
                attempt,
                durationMs,
            )
        }
    }

    private fun logFailure(
        userId: Long,
        chargeId: String,
        reason: RefundReason,
        attempt: Int,
        cause: Throwable,
    ) {
        if (reason.detail != null) {
            logger.error(
                "refund failed: userId={} chargeId={} reason={} attempt={} detail={}",
                userId,
                chargeId,
                reason.code,
                attempt,
                reason.detail,
                cause,
            )
        } else {
            logger.error(
                "refund failed: userId={} chargeId={} reason={} attempt={}",
                userId,
                chargeId,
                reason.code,
                attempt,
                cause,
            )
        }
    }

    private sealed interface BeginResult {
        data class Start(
            val entry: RefundJournalEntry.InProgress,
        ) : BeginResult

        data class Skip(
            val previous: RefundJournalEntry?,
        ) : BeginResult
    }

    private sealed interface RefundJournalEntry {
        val reason: RefundReason
        val attempt: Int

        data class InProgress(
            override val reason: RefundReason,
            val startedAt: Instant,
            override val attempt: Int,
        ) : RefundJournalEntry

        data class Succeeded(
            override val reason: RefundReason,
            override val attempt: Int,
            val duration: Duration,
            val completedAt: Instant,
        ) : RefundJournalEntry

        data class Failed(
            override val reason: RefundReason,
            override val attempt: Int,
            val lastError: String?,
        ) : RefundJournalEntry
    }

    private fun RefundJournalEntry?.status(): String =
        when (this) {
            null -> "none"
            is RefundJournalEntry.InProgress -> "in_progress"
            is RefundJournalEntry.Succeeded -> "succeeded"
            is RefundJournalEntry.Failed -> "failed"
        }
}

sealed interface RefundReason {
    val code: String
    val detail: String?

    data class Validation(
        override val detail: String,
    ) : RefundReason {
        override val code: String = "validation_failure"
    }

    data class Draw(
        override val detail: String,
    ) : RefundReason {
        override val code: String = "draw_failure"
    }

    data class Award(
        override val detail: String,
    ) : RefundReason {
        override val code: String = "award_failure"
    }
}
