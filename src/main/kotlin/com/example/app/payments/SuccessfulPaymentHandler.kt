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
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class SuccessfulPaymentHandler(
    private val telegramApiClient: TelegramApiClient,
    private val rngService: RngService,
    private val casesRepository: CasesRepository,
    private val awardService: AwardService,
    private val paymentsConfig: PaymentsConfig,
    meterRegistry: MeterRegistry,
) {
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
            processedPayments.remove(chargeId, ProcessedPaymentState.InProgress)
            metrics.markFailure()
            logValidationFailure(updateId, chargeId, message, validation)
            return
        }

        val payload = (validation as ValidationResult.Success).payload
        val providerChargeId = payment.provider_payment_charge_id?.trim()?.takeUnless { it.isEmpty() }

        try {
            val drawResult = rngService.draw(payload.caseId, payload.userId, payload.nonce)
            val plan =
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
            processedPayments.remove(chargeId, ProcessedPaymentState.InProgress)
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
            return ValidationResult.Failure("invalid_payload", cause)
        }

        val chatId = message.chat.id
        val fromId = message.from?.id
        if (payload.userId != chatId) {
            return ValidationResult.Failure(
                "user_mismatch expected=${payload.userId} actual=$chatId",
            )
        }
        if (fromId != null && fromId != payload.userId) {
            return ValidationResult.Failure(
                "sender_mismatch expected=${payload.userId} actual=$fromId",
            )
        }
        if (payload.nonce.isBlank()) {
            return ValidationResult.Failure("nonce_blank")
        }
        if (payload.caseId.isBlank()) {
            return ValidationResult.Failure("case_id_blank")
        }

        if (!payment.currency.equals(paymentsConfig.currency, ignoreCase = false)) {
            return ValidationResult.Failure(
                "invalid_currency expected=${paymentsConfig.currency} actual=${payment.currency}",
            )
        }

        val case = casesRepository.get(payload.caseId)
            ?: return ValidationResult.Failure("case_not_found caseId=${payload.caseId}")

        if (payment.total_amount != case.priceStars) {
            return ValidationResult.Failure(
                "invalid_amount expected=${case.priceStars} actual=${payment.total_amount}",
            )
        }

        return ValidationResult.Success(payload = payload)
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
        val plan = (state as? ProcessedPaymentState.Completed)?.plan
        logger.info(
            "successful payment duplicate: updateId={} chargeId={} userId={} caseId={}",
            updateId,
            chargeId,
            plan?.userId,
            plan?.caseId,
        )
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

        data class Failure(val reason: String, val cause: Throwable? = null) : ValidationResult
    }

    private sealed interface ProcessedPaymentState {
        data class Completed(val plan: AwardPlan) : ProcessedPaymentState

        object InProgress : ProcessedPaymentState
    }

    companion object {
        private const val COMPONENT_VALUE = "payments"
        private val logger = LoggerFactory.getLogger(SuccessfulPaymentHandler::class.java)
    }
}
