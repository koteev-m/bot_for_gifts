package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.payments.dto.PaymentPayload
import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.telegram.PreCheckoutQueryDto
import com.example.giftsbot.telegram.TelegramApiClient
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

class PreCheckoutHandler(
    private val telegramApiClient: TelegramApiClient,
    private val casesRepository: CasesRepository,
    meterRegistry: MeterRegistry,
) {
    private val metrics = PreCheckoutMetrics(meterRegistry)

    suspend fun handle(
        updateId: Long,
        query: PreCheckoutQueryDto,
    ) = withTimeout(RESPONSE_TIMEOUT_MILLIS) {
        when (val validation = validate(query)) {
            is ValidationResult.Success -> respondSuccess(updateId, query, validation)
            is ValidationResult.Failure -> respondFailure(updateId, query, validation)
        }
    }

    private suspend fun respondSuccess(
        updateId: Long,
        query: PreCheckoutQueryDto,
        success: ValidationResult.Success,
    ) {
        metrics.markSuccess()
        logger.info(
            "pre-checkout approved: updateId={} queryId={} caseId={} userId={} amount={} expectedAmount={}",
            updateId,
            query.id,
            success.payload.caseId,
            query.from.id,
            query.total_amount,
            success.caseConfig.priceStars,
        )
        telegramApiClient.answerPreCheckoutQuery(
            queryId = query.id,
            ok = true,
            errorMessage = null,
        )
    }

    private suspend fun respondFailure(
        updateId: Long,
        query: PreCheckoutQueryDto,
        failure: ValidationResult.Failure,
    ) {
        metrics.markFailure()
        if (failure.cause != null) {
            logger.warn(
                "pre-checkout rejected: updateId={} queryId={} userId={} reason={}",
                updateId,
                query.id,
                query.from.id,
                failure.reason,
                failure.cause,
            )
        } else {
            logger.warn(
                "pre-checkout rejected: updateId={} queryId={} userId={} reason={}",
                updateId,
                query.id,
                query.from.id,
                failure.reason,
            )
        }
        telegramApiClient.answerPreCheckoutQuery(
            queryId = query.id,
            ok = false,
            errorMessage = failure.userMessage,
        )
    }

    private fun validate(query: PreCheckoutQueryDto): ValidationResult {
        val payloadOutcome = decodePayload(query)
        return when (payloadOutcome) {
            is PayloadOutcome.Invalid -> payloadOutcome.failure
            is PayloadOutcome.Valid -> validatePayload(query, payloadOutcome.payload)
        }
    }

    private fun validatePayload(
        query: PreCheckoutQueryDto,
        payload: PaymentPayload,
    ): ValidationResult {
        val failure = validateConsistency(query, payload)
        return failure ?: resolveCase(query, payload)
    }

    private fun decodePayload(query: PreCheckoutQueryDto): PayloadOutcome {
        val result = runCatching { PaymentPayload.decode(query.invoice_payload) }
        val payload = result.getOrNull()
        if (payload != null) {
            return PayloadOutcome.Valid(payload)
        }
        val failure =
            ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "invalid_payload",
                cause = result.exceptionOrNull(),
            )
        return PayloadOutcome.Invalid(failure)
    }

    private fun validateConsistency(
        query: PreCheckoutQueryDto,
        payload: PaymentPayload,
    ): ValidationResult.Failure? {
        val reason =
            when {
                payload.userId != query.from.id ->
                    "user_mismatch expected=${payload.userId} actual=${query.from.id}"
                payload.nonce.isBlank() -> "nonce_blank"
                payload.caseId.isBlank() -> "case_id_blank"
                else -> null
            } ?: return null
        return ValidationResult.Failure(userMessage = DEFAULT_ERROR_MESSAGE, reason = reason)
    }

    private fun resolveCase(
        query: PreCheckoutQueryDto,
        payload: PaymentPayload,
    ): ValidationResult {
        val case = casesRepository.get(payload.caseId)
        val failureReason =
            when {
                case == null -> "case_not_found caseId=${payload.caseId}"
                query.currency != STARS_CURRENCY_CODE ->
                    "invalid_currency expected=$STARS_CURRENCY_CODE actual=${query.currency}"
                query.total_amount != case.priceStars ->
                    "invalid_amount expected=${case.priceStars} actual=${query.total_amount}"
                else -> null
            }
        return if (failureReason == null) {
            ValidationResult.Success(payload = payload, caseConfig = case!!)
        } else {
            ValidationResult.Failure(userMessage = DEFAULT_ERROR_MESSAGE, reason = failureReason)
        }
    }

    private class PreCheckoutMetrics(
        registry: MeterRegistry,
    ) {
        private val componentTag = MetricsTags.COMPONENT to COMPONENT_VALUE
        private val successCounter =
            Metrics.counter(registry, MetricsNames.PRE_CHECKOUT_TOTAL, componentTag, MetricsTags.RESULT to RESULT_OK)
        private val failureCounter =
            Metrics.counter(registry, MetricsNames.PRE_CHECKOUT_TOTAL, componentTag, MetricsTags.RESULT to RESULT_FAIL)

        fun markSuccess() {
            successCounter.increment()
        }

        fun markFailure() {
            failureCounter.increment()
        }
    }

    private sealed interface ValidationResult {
        data class Success(
            val payload: PaymentPayload,
            val caseConfig: CaseConfig,
        ) : ValidationResult

        data class Failure(
            val userMessage: String,
            val reason: String,
            val cause: Throwable? = null,
        ) : ValidationResult
    }

    private sealed interface PayloadOutcome {
        data class Valid(
            val payload: PaymentPayload,
        ) : PayloadOutcome

        data class Invalid(
            val failure: ValidationResult.Failure,
        ) : PayloadOutcome
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MILLIS = 10_000L
        private const val COMPONENT_VALUE = "payments"
        private const val RESULT_OK = "ok"
        private const val RESULT_FAIL = "fail"
        private const val DEFAULT_ERROR_MESSAGE = "Payment rejected: invalid parameters."
        private val logger = LoggerFactory.getLogger(PreCheckoutHandler::class.java)
    }
}
