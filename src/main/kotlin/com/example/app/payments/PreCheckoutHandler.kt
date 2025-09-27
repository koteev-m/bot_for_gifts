package com.example.app.payments

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.payments.dto.PaymentPayload
import com.example.app.payments.STARS_CURRENCY_CODE
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

    suspend fun handle(updateId: Long, query: PreCheckoutQueryDto) =
        withTimeout(RESPONSE_TIMEOUT_MILLIS) {
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
        val payloadResult = runCatching { PaymentPayload.decode(query.invoice_payload) }
        val payload = payloadResult.getOrElse { cause ->
            return ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "invalid_payload",
                cause = cause,
            )
        }

        if (payload.userId != query.from.id) {
            return ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "user_mismatch expected=${payload.userId} actual=${query.from.id}",
            )
        }
        if (payload.nonce.isBlank()) {
            return ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "nonce_blank",
            )
        }
        if (payload.caseId.isBlank()) {
            return ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "case_id_blank",
            )
        }

        val case = casesRepository.get(payload.caseId)
            ?: return ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "case_not_found caseId=${payload.caseId}",
            )

        if (query.currency != STARS_CURRENCY_CODE) {
            return ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "invalid_currency expected=$STARS_CURRENCY_CODE actual=${query.currency}",
            )
        }
        if (query.total_amount != case.priceStars) {
            return ValidationResult.Failure(
                userMessage = DEFAULT_ERROR_MESSAGE,
                reason = "invalid_amount expected=${case.priceStars} actual=${query.total_amount}",
            )
        }

        return ValidationResult.Success(payload = payload, caseConfig = case)
    }

    private class PreCheckoutMetrics(registry: MeterRegistry) {
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
        data class Success(val payload: PaymentPayload, val caseConfig: CaseConfig) : ValidationResult

        data class Failure(
            val userMessage: String,
            val reason: String,
            val cause: Throwable? = null,
        ) : ValidationResult
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
