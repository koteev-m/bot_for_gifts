package com.example.giftsbot.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

private const val DEFAULT_BASE_URL = "https://api.telegram.org"
private const val REQUEST_TIMEOUT_MILLIS = 30_000L
private const val CONNECT_TIMEOUT_MILLIS = 10_000L
private const val SOCKET_TIMEOUT_MILLIS = 30_000L
private const val MIN_TIMEOUT_SECONDS = 1
private const val MAX_TIMEOUT_SECONDS = 50
private const val SERVER_ERROR_START = 500
private const val SERVER_ERROR_END = 599
private const val CLIENT_ERROR_START = 400
private const val TOO_MANY_REQUESTS_CODE = 429
private const val SUCCESS_STATUS_START = 200
private const val SUCCESS_STATUS_END = 299
private const val MAX_ATTEMPTS = 4
private const val INITIAL_RETRY_DELAY_MS = 200L
private const val MAX_RETRY_DELAY_MS = 1_600L
private const val MAX_ERROR_BODY_PREVIEW = 512
private const val NANOS_IN_MILLISECOND = 1_000_000L

@Suppress("TooManyFunctions")
class TelegramApiClient(
    private val botToken: String,
    private val http: HttpClient =
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
            }
        },
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val logger = LoggerFactory.getLogger(TelegramApiClient::class.java)
    private val apiBaseUrl: String = "$baseUrl/bot$botToken"

    suspend fun setWebhook(
        url: String,
        secretToken: String,
        allowedUpdates: List<String>? = null,
        maxConnections: Int? = null,
        dropPendingUpdates: Boolean = false,
    ): Boolean {
        logger.debug(
            "setWebhook request maxConnections={} allowedUpdates={} hasUrl={} dropPendingUpdates={}",
            maxConnections,
            allowedUpdates,
            url.isNotBlank(),
            dropPendingUpdates,
        )
        val result =
            execute<Boolean>(
                methodName = "setWebhook",
                body =
                    SetWebhookRequest(
                        url = url,
                        secretToken = secretToken,
                        allowedUpdates = allowedUpdates,
                        maxConnections = maxConnections,
                        dropPendingUpdates = dropPendingUpdates.takeIf { it },
                    ),
            )
        if (result) {
            return true
        }
        throw TelegramApiException("Telegram API setWebhook returned false result")
    }

    suspend fun deleteWebhook(dropPending: Boolean = false): Boolean {
        logger.debug("deleteWebhook request dropPending={}", dropPending)
        val result =
            execute<Boolean>(
                methodName = "deleteWebhook",
                body = DeleteWebhookRequest(dropPendingUpdates = dropPending),
            )
        if (result) {
            return true
        }
        throw TelegramApiException("Telegram API deleteWebhook returned false result")
    }

    suspend fun getWebhookInfo(): WebhookInfoDto {
        val webhookInfo = execute<WebhookInfoDto>(methodName = "getWebhookInfo")
        logger.debug(
            "getWebhookInfo response pendingUpdateCount={} hasCustomCertificate={}",
            webhookInfo.pending_update_count,
            webhookInfo.has_custom_certificate,
        )
        return webhookInfo
    }

    suspend fun createInvoiceLink(request: CreateInvoiceLinkRequest): String {
        val priceAmount = request.prices.singleOrNull()?.amount ?: request.prices.sumOf { it.amount }
        logger.debug(
            "createInvoiceLink request title={} currency={} priceAmount={} pricesCount={} " +
                "hasBusinessConnectionId={} receiptEnabled={} payloadSize={}",
            request.title,
            request.currency,
            priceAmount,
            request.prices.size,
            request.businessConnectionId != null,
            request.receiptEnabled == true,
            request.payload.length,
        )
        return execute(methodName = "createInvoiceLink", body = request)
    }

    suspend fun answerPreCheckoutQuery(
        queryId: String,
        ok: Boolean,
        errorMessage: String? = null,
    ): Boolean {
        val sanitizedError = errorMessage?.trim()?.takeUnless { it.isEmpty() }?.takeIf { !ok }
        logger.debug(
            "answerPreCheckoutQuery request id={} ok={} hasErrorMessage={}",
            queryId,
            ok,
            sanitizedError != null,
        )
        val result =
            execute<Boolean>(
                methodName = "answerPreCheckoutQuery",
                body =
                    AnswerPreCheckoutQueryRequest(
                        preCheckoutQueryId = queryId,
                        ok = ok,
                        errorMessage = sanitizedError,
                    ),
            )
        if (result) {
            return true
        }
        throw TelegramApiException("Telegram API answerPreCheckoutQuery returned false result")
    }

    suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>? = null,
    ): List<UpdateDto> {
        require(timeoutSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            "timeoutSeconds must be between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS"
        }
        logger.debug(
            "getUpdates request offset={} timeoutSeconds={} allowedUpdates={}",
            offset,
            timeoutSeconds,
            allowedUpdates,
        )
        return execute(
            methodName = "getUpdates",
            body =
                GetUpdatesRequest(
                    offset = offset,
                    timeout = timeoutSeconds,
                    allowedUpdates = allowedUpdates,
                ),
        )
    }

    private suspend inline fun <reified T> execute(
        methodName: String,
        body: Any? = null,
    ): T {
        val url = buildUrl(methodName)
        val response = withRetry(methodName) { performRequest(url, body) }
        return parseResponse(methodName, response)
    }

    private fun buildUrl(method: String): String = "$apiBaseUrl/$method"

    private suspend fun performRequest(
        path: String,
        body: Any?,
    ): HttpResponse =
        http.request(path) {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> parseResponse(
        methodName: String,
        response: HttpResponse,
    ): T {
        if (!response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val message =
                buildString {
                    append("Telegram API ")
                    append(methodName)
                    append(" HTTP ")
                    append(response.status.value)
                    if (bodyText.isNotBlank()) {
                        append(": ")
                        append(bodyText.take(MAX_ERROR_BODY_PREVIEW))
                    }
                }
            throw TelegramApiException(message)
        }
        val apiResponse: ApiResponse<T> = response.body()
        if (!apiResponse.ok) {
            val description = apiResponse.description ?: "Unknown Telegram error"
            logger.warn("Telegram API {} business error: {}", methodName, description)
            throw TelegramApiException(description)
        }
        return apiResponse.result ?: nullResultError(methodName)
    }

    private suspend fun withRetry(
        methodName: String,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        var attempt = 1
        var delayMillis = INITIAL_RETRY_DELAY_MS
        while (attempt <= MAX_ATTEMPTS) {
            val hasAttemptsLeft = attempt < MAX_ATTEMPTS
            val startTime = System.nanoTime()
            when (val result = runAttempt(methodName, block, attempt, hasAttemptsLeft, startTime)) {
                is AttemptResult.Success -> return result.response
                is AttemptResult.Retry -> {
                    delay(delayMillis)
                    delayMillis = nextDelay(delayMillis)
                    attempt += 1
                }
                is AttemptResult.Failure -> throw result.cause
            }
        }
        throw TelegramApiException("Telegram API $methodName failed after $MAX_ATTEMPTS attempts")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runAttempt(
        methodName: String,
        block: suspend () -> HttpResponse,
        attempt: Int,
        hasAttemptsLeft: Boolean,
        startTime: Long,
    ): AttemptResult =
        try {
            val response = block()
            val durationMs = elapsedMillis(startTime)
            logger.handleResponse(methodName, response, attempt, hasAttemptsLeft, durationMs)
        } catch (cause: HttpRequestTimeoutException) {
            logger.handleRetryableException(methodName, cause, attempt, hasAttemptsLeft, startTime)
        } catch (cause: ConnectTimeoutException) {
            logger.handleRetryableException(methodName, cause, attempt, hasAttemptsLeft, startTime)
        } catch (cause: UnresolvedAddressException) {
            logger.handleRetryableException(methodName, cause, attempt, hasAttemptsLeft, startTime)
        } catch (cause: ServerResponseException) {
            logger.handleRetryableException(methodName, cause, attempt, hasAttemptsLeft, startTime)
        } catch (cause: ClientRequestException) {
            logger.handleRetryableException(methodName, cause, attempt, hasAttemptsLeft, startTime)
        } catch (cause: IOException) {
            logger.handleRetryableException(methodName, cause, attempt, hasAttemptsLeft, startTime)
        } catch (cause: Throwable) {
            val durationMs = elapsedMillis(startTime)
            logger.error(
                "Telegram API {} unexpected failure: type={} durationMs={} attempt={}",
                methodName,
                cause.javaClass.simpleName,
                durationMs,
                attempt,
                cause,
            )
            AttemptResult.Failure(cause)
        }

    object AllowedUpdates {
        const val MESSAGE = "message"
        const val PRE_CHECKOUT_QUERY = "pre_checkout_query"
        const val SUCCESSFUL_PAYMENT = "successful_payment"
        val DEFAULT: List<String> = listOf(MESSAGE, PRE_CHECKOUT_QUERY, SUCCESSFUL_PAYMENT)
    }
}

class TelegramApiException(
    message: String,
) : RuntimeException(message)

@Serializable
private data class SetWebhookRequest(
    val url: String,
    @SerialName("secret_token")
    val secretToken: String,
    @SerialName("allowed_updates")
    val allowedUpdates: List<String>? = null,
    @SerialName("max_connections")
    val maxConnections: Int? = null,
    @SerialName("drop_pending_updates")
    val dropPendingUpdates: Boolean? = null,
)

@Serializable
private data class DeleteWebhookRequest(
    @SerialName("drop_pending_updates")
    val dropPendingUpdates: Boolean,
)

@Serializable
private data class AnswerPreCheckoutQueryRequest(
    @SerialName("pre_checkout_query_id")
    val preCheckoutQueryId: String,
    val ok: Boolean,
    @SerialName("error_message")
    val errorMessage: String? = null,
)

@Serializable
private data class GetUpdatesRequest(
    val offset: Long?,
    val timeout: Int,
    @SerialName("allowed_updates")
    val allowedUpdates: List<String>? = null,
)

@Serializable
data class CreateInvoiceLinkRequest(
    val title: String,
    val description: String,
    val payload: String,
    val currency: String,
    val prices: List<LabeledPrice>,
    @SerialName("provider_token")
    val providerToken: String? = null,
    @SerialName("business_connection_id")
    val businessConnectionId: String? = null,
    @SerialName("receipt_enabled")
    val receiptEnabled: Boolean? = null,
)

@Serializable
data class LabeledPrice(
    val label: String,
    val amount: Long,
)

private fun shouldRetry(
    response: HttpResponse?,
    cause: Throwable?,
): Boolean {
    if (response != null && response.status.value in SERVER_ERROR_START..SERVER_ERROR_END) {
        return true
    }
    return when (cause) {
        is HttpRequestTimeoutException -> true
        is ConnectTimeoutException -> true
        is UnresolvedAddressException -> true
        is IOException -> true
        is ServerResponseException ->
            cause.response.status.value in SERVER_ERROR_START..SERVER_ERROR_END
        else -> false
    }
}

private fun Logger.handleException(
    methodName: String,
    cause: Throwable,
    attempt: Int,
    hasAttemptsLeft: Boolean,
    durationMs: Long,
): AttemptResult {
    val willRetry = shouldRetry(null, cause) && hasAttemptsLeft
    val message = "Telegram API {} failure: type={} durationMs={} attempt={} willRetry={}"
    return if (willRetry) {
        warn(message, methodName, cause.javaClass.simpleName, durationMs, attempt, true, cause)
        AttemptResult.Retry(cause)
    } else {
        error(message, methodName, cause.javaClass.simpleName, durationMs, attempt, false, cause)
        AttemptResult.Failure(cause)
    }
}

private fun Logger.handleRetryableException(
    methodName: String,
    cause: Throwable,
    attempt: Int,
    hasAttemptsLeft: Boolean,
    startTime: Long,
): AttemptResult =
    handleException(
        methodName,
        cause,
        attempt,
        hasAttemptsLeft,
        elapsedMillis(startTime),
    )

private fun Logger.handleResponse(
    methodName: String,
    response: HttpResponse,
    attempt: Int,
    hasAttemptsLeft: Boolean,
    durationMs: Long,
): AttemptResult {
    val retryable = shouldRetry(response, null)
    logStatus(methodName, response.status, durationMs, attempt, retryable && hasAttemptsLeft)
    return when {
        retryable && hasAttemptsLeft ->
            AttemptResult.Retry(
                TelegramApiException(
                    "Telegram API $methodName returned HTTP ${response.status.value}",
                ),
            )
        retryable ->
            AttemptResult.Failure(
                TelegramApiException(
                    "Telegram API $methodName returned HTTP ${response.status.value}",
                ),
            )
        else -> AttemptResult.Success(response)
    }
}

private fun Logger.logStatus(
    methodName: String,
    status: HttpStatusCode,
    durationMs: Long,
    attempt: Int,
    willRetry: Boolean,
) {
    when {
        status.value >= SERVER_ERROR_START -> {
            val message = "Telegram API {} status={} durationMs={} attempt={} willRetry={}"
            if (willRetry) {
                warn(message, methodName, status.value, durationMs, attempt, true)
            } else {
                error(message, methodName, status.value, durationMs, attempt, false)
            }
        }
        status.value == TOO_MANY_REQUESTS_CODE ->
            warn(
                "Telegram API {} status={} durationMs={} attempt={}",
                methodName,
                status.value,
                durationMs,
                attempt,
            )
        status.value >= CLIENT_ERROR_START ->
            warn(
                "Telegram API {} status={} durationMs={} attempt={}",
                methodName,
                status.value,
                durationMs,
                attempt,
            )
        else ->
            info(
                "Telegram API {} status={} durationMs={}",
                methodName,
                status.value,
                durationMs,
            )
    }
}

private fun nullResultError(methodName: String): Nothing =
    throw TelegramApiException("Telegram API $methodName returned null result")

private fun nextDelay(current: Long): Long = (current * 2).coerceAtMost(MAX_RETRY_DELAY_MS)

private fun elapsedMillis(startTime: Long): Long = (System.nanoTime() - startTime) / NANOS_IN_MILLISECOND

private fun HttpStatusCode.isSuccess(): Boolean = value in SUCCESS_STATUS_START..SUCCESS_STATUS_END

private sealed interface AttemptResult {
    data class Success(
        val response: HttpResponse,
    ) : AttemptResult

    data class Retry(
        val cause: Throwable,
    ) : AttemptResult

    data class Failure(
        val cause: Throwable,
    ) : AttemptResult
}
