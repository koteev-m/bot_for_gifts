package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.payments.dto.PaymentPayload
import com.example.app.payments.PaymentSupport
import com.example.app.payments.RefundReason
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.rng.RngService
import com.example.giftsbot.rng.buildUserReceiptText
import com.example.giftsbot.telegram.MessageDto
import com.example.giftsbot.telegram.SuccessfulPaymentDto
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class SuccessfulPaymentHandler(
    private val telegramApiClient: TelegramApiClient,
    private val rngService: RngService,
    private val casesRepository: CasesRepository,
    private val awardService: AwardService,
    paymentSupport: PaymentSupport,
    meterRegistry: MeterRegistry,
) {
    private val refundService = paymentSupport.refundService
    private val paymentsConfig = paymentSupport.config
    private val metrics = SuccessfulPaymentMetrics(meterRegistry)
    private val processedPayments = ConcurrentHashMap<String, ProcessedPaymentState>()

    suspend fun handle(updateId: Long, message: MessageDto) {
        val payment = message.successful_payment
        if (payment == null) {
            metrics.markFailure()
            logger.warn(
                "successful payment missing: updateId={} messageId={} chatId={}",
                updateId,
                message.message_id,
                message.chat.id,
            )
            return
        }

        val chargeId = payment.telegram_payment_charge_id.trim()
        if (chargeId.isEmpty()) {
            metrics.markFailure()
            logger.warn(
                "successful payment rejected: updateId={} reason={} messageId={} chatId={}",
                updateId,
                "charge_id_blank",
                message.message_id,
                message.chat.id,
            )
            return
        }

        val previousState = processedPayments.putIfAbsent(chargeId, ProcessedPaymentState.InProgress)
        if (previousState != null) {
            metrics.markIdempotent()
            logDuplicate(updateId, chargeId, previousState)
            return
        }

        val validation = validate(message, payment)
        if (validation is ValidationResult.Failure) {
            val refunded = refundAfterValidation(chargeId, validation)
            processedPayments[chargeId] =
                if (refunded) {
                    ProcessedPaymentState.Refunded(RefundReason.Validation(validation.reason))
                } else {
                    ProcessedPaymentState.Failed(validation.reason)
                }
            metrics.markFailure()
            logValidationFailure(updateId, chargeId, message, validation)
            return
        }

        val payload = (validation as ValidationResult.Success).payload
        val providerChargeId = payment.provider_payment_charge_id?.trim()?.takeUnless { it.isEmpty() }

        var plan: AwardPlan? = null
        var awardAttempted = false
        try {
            val drawResult = rngService.draw(payload.caseId, payload.userId, payload.nonce)
            plan =
                AwardPlan(
                    telegramPaymentChargeId = chargeId,
                    providerPaymentChargeId = providerChargeId,
                    totalAmount = payment.total_amount,
                    currency = payment.currency,
                    userId = payload.userId,
                    caseId = payload.caseId,
                    nonce = payload.nonce,
                    resultItemId = drawResult.record.resultItemId,
                    rngRecord = drawResult.record,
                    rngReceipt = drawResult.receipt,
                )

            awardAttempted = true
            awardService.schedule(plan)
            processedPayments[chargeId] = ProcessedPaymentState.Completed(plan)
            metrics.markSuccess()
            logger.info(
                "successful payment processed: updateId={} chargeId={} userId={} caseId={} amount={} resultItemId={}",
                updateId,
                chargeId,
                plan.userId,
                plan.caseId,
                plan.totalAmount,
                plan.resultItemId ?: "-",
            )
            sendReceiptIfEnabled(updateId, message, plan)
        } catch (cause: Throwable) {
            if (cause is CancellationException) {
                processedPayments.remove(chargeId, ProcessedPaymentState.InProgress)
                throw cause
            }
            val detail = failureDetail(cause)
            val refundReason = if (awardAttempted && plan != null) {
                RefundReason.Award(detail)
            } else {
                RefundReason.Draw(detail)
            }
            val refundCurrency = plan?.currency ?: payment.currency
            val refundUserId = plan?.userId ?: payload.userId
            val refunded = attemptRefund(chargeId, refundUserId, refundCurrency, refundReason)
            processedPayments[chargeId] =
                if (refunded) {
                    ProcessedPaymentState.Refunded(refundReason)
                } else {
                    ProcessedPaymentState.Failed(refundReason.detail)
                }
            metrics.markFailure()
            logger.error(
                "successful payment failed: updateId={} chargeId={} userId={} caseId={}",
                updateId,
                chargeId,
                payload.userId,
                payload.caseId,
                cause,
            )
            throw cause
        }
    }

    private fun validate(
        message: MessageDto,
        payment: SuccessfulPaymentDto,
    ): ValidationResult {
        val payloadResult = runCatching { PaymentPayload.decode(payment.invoice_payload) }
        val payload = payloadResult.getOrElse { cause ->
            val context = message.from?.id?.takeIf { it > 0 }?.let { RefundContext(userId = it, currency = payment.currency) }
            return ValidationResult.Failure("invalid_payload", cause, context)
        }
        val refundContext = RefundContext(userId = payload.userId, currency = payment.currency)

        val chatId = message.chat.id
        val fromId = message.from?.id
        if (payload.userId != chatId) {
            return ValidationResult.Failure(
                "user_mismatch expected=${payload.userId} actual=$chatId",
                refundContext = refundContext,
            )
        }
        if (fromId != null && fromId != payload.userId) {
            return ValidationResult.Failure(
                "sender_mismatch expected=${payload.userId} actual=$fromId",
                refundContext = refundContext,
            )
        }
        if (payload.nonce.isBlank()) {
            return ValidationResult.Failure("nonce_blank", refundContext = refundContext)
        }
        if (payload.caseId.isBlank()) {
            return ValidationResult.Failure("case_id_blank", refundContext = refundContext)
        }

        if (!payment.currency.equals(paymentsConfig.currency, ignoreCase = false)) {
            return ValidationResult.Failure(
                "invalid_currency expected=${paymentsConfig.currency} actual=${payment.currency}",
                refundContext = refundContext,
            )
        }

        val case = casesRepository.get(payload.caseId)
            ?: return ValidationResult.Failure("case_not_found caseId=${payload.caseId}", refundContext = refundContext)

        if (payment.total_amount != case.priceStars) {
            return ValidationResult.Failure(
                "invalid_amount expected=${case.priceStars} actual=${payment.total_amount}",
                refundContext = refundContext,
            )
        }

        return ValidationResult.Success(payload = payload)
    }

    private suspend fun refundAfterValidation(
        chargeId: String,
        failure: ValidationResult.Failure,
    ): Boolean {
        val context = failure.refundContext
        if (context == null) {
            logger.info(
                "refund skipped after validation: chargeId={} reason={} cause=missing_context",
                chargeId,
                failure.reason,
            )
            return false
        }
        return attemptRefund(
            chargeId = chargeId,
            userId = context.userId,
            currency = context.currency,
            reason = RefundReason.Validation(failure.reason),
        )
    }

    private suspend fun attemptRefund(
        chargeId: String,
        userId: Long,
        currency: String,
        reason: RefundReason,
    ): Boolean {
        if (!currency.equals(STARS_CURRENCY_CODE, ignoreCase = true)) {
            logger.warn(
                "refund skipped: chargeId={} userId={} reason={} currency={}",
                chargeId,
                userId,
                reason.code,
                currency,
            )
            return false
        }
        return runCatching {
            refundService.refundStarPayment(
                userId = userId,
                telegramPaymentChargeId = chargeId,
                reason = reason,
            )
        }.onFailure { cause ->
            if (reason.detail != null) {
                logger.error(
                    "refund attempt failed: chargeId={} userId={} reason={} detail={}",
                    chargeId,
                    userId,
                    reason.code,
                    reason.detail,
                    cause,
                )
            } else {
                logger.error(
                    "refund attempt failed: chargeId={} userId={} reason={}",
                    chargeId,
                    userId,
                    reason.code,
                    cause,
                )
            }
        }.isSuccess
    }

    private fun failureDetail(cause: Throwable): String =
        cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName

    private suspend fun sendReceiptIfEnabled(
        updateId: Long,
        message: MessageDto,
        plan: AwardPlan,
    ) {
        if (!paymentsConfig.receiptEnabled) {
            return
        }
        val text = buildUserReceiptText(plan.rngReceipt, plan.resultItemId)
        runCatching {
            telegramApiClient.sendMessage(
                chatId = message.chat.id,
                text = text,
                disableNotification = true,
                replyToMessageId = message.message_id,
            )
        }.onSuccess {
            logger.info(
                "successful payment receipt sent: updateId={} chargeId={} chatId={} messageId={} textLength={}",
                updateId,
                plan.telegramPaymentChargeId,
                message.chat.id,
                message.message_id,
                text.length,
            )
        }.onFailure { cause ->
            logger.warn(
                "successful payment receipt failed: updateId={} chargeId={} chatId={} messageId={} reason={}",
                updateId,
                plan.telegramPaymentChargeId,
                message.chat.id,
                message.message_id,
                cause.message,
                cause,
            )
        }
    }

    private fun logDuplicate(
        updateId: Long,
        chargeId: String,
        state: ProcessedPaymentState,
    ) {
        when (state) {
            is ProcessedPaymentState.Completed -> {
                val plan = state.plan
                logger.info(
                    "successful payment duplicate: updateId={} chargeId={} userId={} caseId={}",
                    updateId,
                    chargeId,
                    plan.userId,
                    plan.caseId,
                )
            }
            is ProcessedPaymentState.Refunded -> {
                if (state.reason.detail != null) {
                    logger.info(
                        "successful payment duplicate refunded: updateId={} chargeId={} reason={} detail={}",
                        updateId,
                        chargeId,
                        state.reason.code,
                        state.reason.detail,
                    )
                } else {
                    logger.info(
                        "successful payment duplicate refunded: updateId={} chargeId={} reason={}",
                        updateId,
                        chargeId,
                        state.reason.code,
                    )
                }
            }
            is ProcessedPaymentState.Failed -> {
                logger.info(
                    "successful payment duplicate failed: updateId={} chargeId={} reason={}",
                    updateId,
                    chargeId,
                    state.reason ?: "-",
                )
            }
            ProcessedPaymentState.InProgress -> {
                logger.info(
                    "successful payment duplicate: updateId={} chargeId={} state=in_progress",
                    updateId,
                    chargeId,
                )
            }
        }
    }

    private fun logValidationFailure(
        updateId: Long,
        chargeId: String,
        message: MessageDto,
        failure: ValidationResult.Failure,
    ) {
        val cause = failure.cause
        if (cause != null) {
            logger.warn(
                "successful payment rejected: updateId={} chargeId={} messageId={} chatId={} reason={}",
                updateId,
                chargeId,
                message.message_id,
                message.chat.id,
                failure.reason,
                cause,
            )
        } else {
            logger.warn(
                "successful payment rejected: updateId={} chargeId={} messageId={} chatId={} reason={}",
                updateId,
                chargeId,
                message.message_id,
                message.chat.id,
                failure.reason,
            )
        }
    }

    private class SuccessfulPaymentMetrics(registry: MeterRegistry) {
        private val componentTag = MetricsTags.COMPONENT to COMPONENT_VALUE
        private val successCounter =
            Metrics.counter(registry, MetricsNames.PAY_SUCCESS_TOTAL, componentTag)
        private val idempotentCounter =
            Metrics.counter(registry, MetricsNames.PAY_SUCCESS_IDEMPOTENT_TOTAL, componentTag)
        private val failureCounter =
            Metrics.counter(registry, MetricsNames.PAY_SUCCESS_FAIL_TOTAL, componentTag)

        fun markSuccess() {
            successCounter.increment()
        }

        fun markIdempotent() {
            idempotentCounter.increment()
        }

        fun markFailure() {
            failureCounter.increment()
        }
    }

    private sealed interface ValidationResult {
        data class Success(val payload: PaymentPayload) : ValidationResult

        data class Failure(
            val reason: String,
            val cause: Throwable? = null,
            val refundContext: RefundContext? = null,
        ) : ValidationResult
    }

    private data class RefundContext(
        val userId: Long,
        val currency: String,
    )

    private sealed interface ProcessedPaymentState {
        data class Completed(val plan: AwardPlan) : ProcessedPaymentState

        data class Refunded(val reason: RefundReason) : ProcessedPaymentState

        data class Failed(val reason: String?) : ProcessedPaymentState

        object InProgress : ProcessedPaymentState
    }

    companion object {
        private const val COMPONENT_VALUE = "payments"
        private val logger = LoggerFactory.getLogger(SuccessfulPaymentHandler::class.java)
    }
}
