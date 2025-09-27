package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.payments.dto.PaymentPayload
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.rng.RngService
import com.example.giftsbot.rng.buildUserReceiptText
import com.example.giftsbot.telegram.MessageDto
import com.example.giftsbot.telegram.SuccessfulPaymentDto
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class SuccessfulPaymentHandler(
    private val telegramApiClient: TelegramApiClient,
    private val rngService: RngService,
    casesRepository: CasesRepository,
    private val awardService: AwardService,
    paymentSupport: PaymentSupport,
    meterRegistry: MeterRegistry,
) {
    private val refundService = paymentSupport.refundService
    private val paymentsConfig = paymentSupport.config
    private val validator = PaymentValidator(casesRepository, paymentsConfig)
    private val metrics = SuccessfulPaymentMetrics(meterRegistry)
    private val processedPayments = ConcurrentHashMap<String, ProcessedPaymentState>()

    suspend fun handle(
        updateId: Long,
        message: MessageDto,
    ) {
        when (val decision = prepareProcessing(message)) {
            is ProcessingDecision.Process ->
                processPayment(updateId, message, decision)
            is ProcessingDecision.ValidationFailed ->
                handleValidationFailure(decision.chargeId, updateId, message, decision.failure)
            is ProcessingDecision.Duplicate -> {
                metrics.markIdempotent()
                logDuplicate(logger, updateId, decision.chargeId, decision.previousState)
            }
            is ProcessingDecision.MissingPayment -> {
                metrics.markFailure()
                logMissingPayment(logger, updateId, decision.message)
            }
            is ProcessingDecision.BlankCharge -> {
                metrics.markFailure()
                logBlankCharge(logger, updateId, decision.message)
            }
        }
    }

    private fun prepareProcessing(message: MessageDto): ProcessingDecision {
        val payment = message.successful_payment
        val chargeId = payment?.telegram_payment_charge_id?.trim()?.takeIf { it.isNotEmpty() }
        val previousState = chargeId?.let { processedPayments.putIfAbsent(it, ProcessedPaymentState.InProgress) }
        return when {
            payment == null -> ProcessingDecision.MissingPayment(message)
            chargeId == null -> ProcessingDecision.BlankCharge(message)
            previousState != null -> ProcessingDecision.Duplicate(chargeId, previousState)
            else ->
                when (val validation = validator.validate(message, payment)) {
                    is ValidationResult.Success -> ProcessingDecision.Process(chargeId, payment, validation.payload)
                    is ValidationResult.Failure -> ProcessingDecision.ValidationFailed(chargeId, validation)
                }
        }
    }

    private suspend fun processPayment(
        updateId: Long,
        message: MessageDto,
        decision: ProcessingDecision.Process,
    ) {
        var plan: AwardPlan? = null
        var awardAttempted = false
        val outcome =
            runCatching {
                plan = createPlan(decision.chargeId, decision.payment, decision.payload)
                awardAttempted = true
                val createdPlan = plan!!
                awardService.schedule(createdPlan)
                createdPlan
            }
        outcome.onSuccess { createdPlan ->
            processedPayments[decision.chargeId] = ProcessedPaymentState.Completed(createdPlan)
            metrics.markSuccess()
            logSuccess(logger, updateId, createdPlan)
            sendReceiptIfEnabled(updateId, message, createdPlan)
        }
        outcome.exceptionOrNull()?.let { cause ->
            handleProcessingFailure(updateId, decision, plan, awardAttempted, cause)
            throw cause
        }
    }

    private fun createPlan(
        chargeId: String,
        payment: SuccessfulPaymentDto,
        payload: PaymentPayload,
    ): AwardPlan {
        val drawResult = rngService.draw(payload.caseId, payload.userId, payload.nonce)
        val providerChargeId = payment.provider_payment_charge_id?.trim()?.takeUnless { it.isEmpty() }
        return AwardPlan(
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
    }

    private suspend fun handleProcessingFailure(
        updateId: Long,
        decision: ProcessingDecision.Process,
        plan: AwardPlan?,
        awardAttempted: Boolean,
        cause: Throwable,
    ) {
        if (cause is CancellationException) {
            processedPayments.remove(decision.chargeId, ProcessedPaymentState.InProgress)
            throw cause
        }
        val detail = exceptionDetail(cause)
        val refundReason =
            if (awardAttempted && plan != null) {
                RefundReason.Award(detail)
            } else {
                RefundReason.Draw(detail)
            }
        val refundCurrency = plan?.currency ?: decision.payment.currency
        val refundUserId = plan?.userId ?: decision.payload.userId
        val refunded = attemptRefund(decision.chargeId, refundUserId, refundCurrency, refundReason)
        processedPayments[decision.chargeId] =
            if (refunded) {
                ProcessedPaymentState.Refunded(refundReason)
            } else {
                ProcessedPaymentState.Failed(refundReason.detail)
            }
        metrics.markFailure()
        logProcessingFailure(logger, updateId, decision, refundUserId, cause)
    }

    private suspend fun handleValidationFailure(
        chargeId: String,
        updateId: Long,
        message: MessageDto,
        failure: ValidationResult.Failure,
    ) {
        val refunded = refundAfterValidation(chargeId, failure)
        processedPayments[chargeId] =
            if (refunded) {
                ProcessedPaymentState.Refunded(RefundReason.Validation(failure.reason))
            } else {
                ProcessedPaymentState.Failed(failure.reason)
            }
        metrics.markFailure()
        logValidationFailure(logger, updateId, chargeId, message, failure)
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
            logReceiptSuccess(logger, updateId, plan, message, text.length)
        }.onFailure { cause ->
            logReceiptFailure(logger, updateId, plan, message, cause)
        }
    }

    private class SuccessfulPaymentMetrics(
        registry: MeterRegistry,
    ) {
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

    companion object {
        private const val COMPONENT_VALUE = "payments"
        private val logger: Logger = LoggerFactory.getLogger(SuccessfulPaymentHandler::class.java)
    }
}

private class PaymentValidator(
    private val casesRepository: CasesRepository,
    private val paymentsConfig: PaymentsConfig,
) {
    fun validate(
        message: MessageDto,
        payment: SuccessfulPaymentDto,
    ): ValidationResult {
        val payloadResult = decodePayload(message, payment)
        if (payloadResult is ValidationResult.Failure) {
            return payloadResult
        }
        val payload = (payloadResult as ValidationResult.Success).payload
        val context = RefundContext(userId = payload.userId, currency = payment.currency)
        val failure =
            validateMessageContext(message, payload, context)
                ?: validateCurrency(payment.currency, context)
                ?: validateCase(payload, payment.total_amount, context)
        return failure ?: ValidationResult.Success(payload)
    }

    private fun decodePayload(
        message: MessageDto,
        payment: SuccessfulPaymentDto,
    ): ValidationResult {
        val result = runCatching { PaymentPayload.decode(payment.invoice_payload) }
        val payload = result.getOrNull()
        if (payload != null) {
            return ValidationResult.Success(payload)
        }
        val refundContext =
            message.from
                ?.id
                ?.takeIf { it > 0 }
                ?.let { RefundContext(userId = it, currency = payment.currency) }
        return ValidationResult.Failure(
            reason = "invalid_payload",
            cause = result.exceptionOrNull(),
            refundContext = refundContext,
        )
    }

    private fun validateMessageContext(
        message: MessageDto,
        payload: PaymentPayload,
        context: RefundContext,
    ): ValidationResult.Failure? {
        val fromId = message.from?.id
        val reason =
            when {
                payload.userId != message.chat.id ->
                    "user_mismatch expected=${payload.userId} actual=${message.chat.id}"
                fromId != null && fromId != payload.userId ->
                    "sender_mismatch expected=${payload.userId} actual=$fromId"
                payload.nonce.isBlank() -> "nonce_blank"
                payload.caseId.isBlank() -> "case_id_blank"
                else -> null
            } ?: return null
        return ValidationResult.Failure(reason = reason, refundContext = context)
    }

    private fun validateCurrency(
        paymentCurrency: String,
        context: RefundContext,
    ): ValidationResult.Failure? =
        if (!paymentCurrency.equals(paymentsConfig.currency, ignoreCase = false)) {
            ValidationResult.Failure(
                reason = "invalid_currency expected=${paymentsConfig.currency} actual=$paymentCurrency",
                refundContext = context,
            )
        } else {
            null
        }

    private fun validateCase(
        payload: PaymentPayload,
        totalAmount: Long,
        context: RefundContext,
    ): ValidationResult.Failure? {
        val case = casesRepository.get(payload.caseId)
        val priceStars = case?.priceStars
        val reason =
            when {
                case == null -> "case_not_found caseId=${payload.caseId}"
                priceStars != null && totalAmount != priceStars ->
                    "invalid_amount expected=$priceStars actual=$totalAmount"
                else -> null
            } ?: return null
        return ValidationResult.Failure(reason = reason, refundContext = context)
    }
}

private sealed interface ProcessingDecision {
    data class Process(
        val chargeId: String,
        val payment: SuccessfulPaymentDto,
        val payload: PaymentPayload,
    ) : ProcessingDecision

    data class ValidationFailed(
        val chargeId: String,
        val failure: ValidationResult.Failure,
    ) : ProcessingDecision

    data class Duplicate(
        val chargeId: String,
        val previousState: ProcessedPaymentState,
    ) : ProcessingDecision

    data class MissingPayment(
        val message: MessageDto,
    ) : ProcessingDecision

    data class BlankCharge(
        val message: MessageDto,
    ) : ProcessingDecision
}

private sealed interface ValidationResult {
    data class Success(
        val payload: PaymentPayload,
    ) : ValidationResult

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
    data class Completed(
        val plan: AwardPlan,
    ) : ProcessedPaymentState

    data class Refunded(
        val reason: RefundReason,
    ) : ProcessedPaymentState

    data class Failed(
        val reason: String?,
    ) : ProcessedPaymentState

    object InProgress : ProcessedPaymentState
}

private fun exceptionDetail(cause: Throwable): String =
    cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName

private fun logMissingPayment(
    logger: Logger,
    updateId: Long,
    message: MessageDto,
) {
    logger.warn(
        "successful payment missing: updateId={} messageId={} chatId={}",
        updateId,
        message.message_id,
        message.chat.id,
    )
}

private fun logBlankCharge(
    logger: Logger,
    updateId: Long,
    message: MessageDto,
) {
    logger.warn(
        "successful payment rejected: updateId={} reason={} messageId={} chatId={}",
        updateId,
        "charge_id_blank",
        message.message_id,
        message.chat.id,
    )
}

private fun logSuccess(
    logger: Logger,
    updateId: Long,
    plan: AwardPlan,
) {
    logger.info(
        "successful payment processed: updateId={} chargeId={} userId={} caseId={} amount={} resultItemId={}",
        updateId,
        plan.telegramPaymentChargeId,
        plan.userId,
        plan.caseId,
        plan.totalAmount,
        plan.resultItemId ?: "-",
    )
}

private fun logProcessingFailure(
    logger: Logger,
    updateId: Long,
    decision: ProcessingDecision.Process,
    userId: Long,
    cause: Throwable,
) {
    logger.error(
        "successful payment failed: updateId={} chargeId={} userId={} caseId={}",
        updateId,
        decision.chargeId,
        userId,
        decision.payload.caseId,
        cause,
    )
}

private fun logDuplicate(
    logger: Logger,
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
            val detail = state.reason.detail
            if (detail != null) {
                logger.info(
                    "successful payment duplicate refunded: updateId={} chargeId={} reason={} detail={}",
                    updateId,
                    chargeId,
                    state.reason.code,
                    detail,
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
    logger: Logger,
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

private fun logReceiptSuccess(
    logger: Logger,
    updateId: Long,
    plan: AwardPlan,
    message: MessageDto,
    textLength: Int,
) {
    logger.info(
        "successful payment receipt sent: updateId={} chargeId={} chatId={} messageId={} textLength={}",
        updateId,
        plan.telegramPaymentChargeId,
        message.chat.id,
        message.message_id,
        textLength,
    )
}

private fun logReceiptFailure(
    logger: Logger,
    updateId: Long,
    plan: AwardPlan,
    message: MessageDto,
    cause: Throwable,
) {
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
