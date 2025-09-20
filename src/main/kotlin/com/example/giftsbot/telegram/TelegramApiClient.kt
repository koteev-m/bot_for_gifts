package com.example.giftsbot.telegram

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class TelegramApiClient(
    private val botToken: String,
    private val http: HttpClient = HttpClient(CIO) {
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
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    },
    private val baseUrl: String = "https://api.telegram.org",
) {
    private val logger = LoggerFactory.getLogger(TelegramApiClient::class.java)
    private val apiBaseUrl = "$baseUrl/bot$botToken"

    suspend fun setWebhook(
        url: String,
        secretToken: String,
        allowedUpdates: List<String>? = null,
        maxConnections: Int? = null,
    ): Boolean {
        logger.debug(
            "setWebhook request maxConnections={} allowedUpdates={} hasUrl={}",
            maxConnections,
            allowedUpdates,
            url.isNotBlank(),
        )
        val result = execute<Boolean>("setWebhook", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(
                SetWebhookRequest(
                    url = url,
                    secret_token = secretToken,
                    allowed_updates = allowedUpdates,
                    max_connections = maxConnections,
                ),
            )
        }
        if (result) {
            return true
        }
        throw TelegramApiException("Telegram API setWebhook returned false result")
    }

    suspend fun deleteWebhook(dropPending: Boolean = false): Boolean {
        logger.debug("deleteWebhook request dropPending={}", dropPending)
        val result = execute<Boolean>("deleteWebhook", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(DeleteWebhookRequest(drop_pending_updates = dropPending))
        }
        if (result) {
            return true
        }
        throw TelegramApiException("Telegram API deleteWebhook returned false result")
    }

    suspend fun getWebhookInfo(): WebhookInfoDto {
        val webhookInfo = execute<WebhookInfoDto>("getWebhookInfo", HttpMethod.Get)
        logger.debug(
            "getWebhookInfo response pendingUpdateCount={} hasCustomCertificate={}",
            webhookInfo.pending_update_count,
            webhookInfo.has_custom_certificate,
        )
        return webhookInfo
    }

    suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>? = null,
    ): List<UpdateDto> {
        require(timeoutSeconds in 1..50) { "timeoutSeconds must be between 1 and 50" }
        logger.debug(
            "getUpdates request offset={} timeoutSeconds={} allowedUpdates={}",
            offset,
            timeoutSeconds,
            allowedUpdates,
        )
        return execute("getUpdates", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(
                GetUpdatesRequest(
                    offset = offset,
                    timeout = timeoutSeconds,
                    allowed_updates = allowedUpdates,
                ),
            )
        }
    }

    private suspend inline fun <reified T> execute(
        methodName: String,
        httpMethod: HttpMethod,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        var attempt = 0
        var delayMillis = INITIAL_RETRY_DELAY_MS
        while (true) {
            attempt += 1
            val startTime = System.nanoTime()
            try {
                val response = http.request("$apiBaseUrl/$methodName") {
                    method = httpMethod
                    configure()
                }
                val durationMs = (System.nanoTime() - startTime) / NANOS_IN_MILLISECOND
                val status = response.status
                val canRetry = status.value in 500..599 && attempt <= MAX_RETRIES
                logStatus(methodName, status, durationMs, attempt, canRetry)
                if (!status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    if (canRetry) {
                        delay(delayMillis)
                        delayMillis = (delayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                        continue
                    }
                    val message = buildString {
                        append("Telegram API ")
                        append(methodName)
                        append(" HTTP ")
                        append(status.value)
                        if (bodyText.isNotBlank()) {
                            append(": ")
                            append(bodyText.take(MAX_ERROR_BODY_PREVIEW))
                        }
                    }
                    throw TelegramApiException(message)
                }

                val apiResponse: ApiResponse<T> = response.body<ApiResponse<T>>()
                if (!apiResponse.ok) {
                    val description = apiResponse.description ?: "Unknown Telegram error"
                    logger.warn("Telegram API {} business error: {}", methodName, description)
                    throw TelegramApiException(description)
                }
                val result = apiResponse.result
                    ?: throw TelegramApiException("Telegram API $methodName returned null result")
                return result
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                val durationMs = (System.nanoTime() - startTime) / NANOS_IN_MILLISECOND
                val isNetworkIssue = exception is IOException || exception is HttpRequestTimeoutException
                val canRetry = isNetworkIssue && attempt <= MAX_RETRIES
                if (isNetworkIssue) {
                    if (canRetry) {
                        logger.warn(
                            "Telegram API {} network error ({}). durationMs={} attempt={} willRetry=true",
                            methodName,
                            exception.message,
                            durationMs,
                            attempt,
                        )
                        delay(delayMillis)
                        delayMillis = (delayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                        continue
                    }
                    logger.error(
                        "Telegram API {} network error ({}). durationMs={} attempt={} willRetry=false",
                        methodName,
                        exception.message,
                        durationMs,
                        attempt,
                        exception,
                    )
                } else {
                    logger.error(
                        "Telegram API {} request failed: {}. durationMs={} attempt={} willRetry=false",
                        methodName,
                        exception.message,
                        durationMs,
                        attempt,
                        exception,
                    )
                }
                throw exception
            }
        }
    }

    private fun logStatus(
        methodName: String,
        status: HttpStatusCode,
        durationMs: Long,
        attempt: Int,
        willRetry: Boolean,
    ) {
        when {
            status.value >= 500 -> {
                if (willRetry) {
                    logger.warn(
                        "Telegram API {} status={} durationMs={} attempt={} willRetry={}",
                        methodName,
                        status.value,
                        durationMs,
                        attempt,
                        willRetry,
                    )
                } else {
                    logger.error(
                        "Telegram API {} status={} durationMs={} attempt={} willRetry={}",
                        methodName,
                        status.value,
                        durationMs,
                        attempt,
                        willRetry,
                    )
                }
            }
            status.value == 429 -> logger.warn(
                "Telegram API {} status={} durationMs={} attempt={} willRetry={}",
                methodName,
                status.value,
                durationMs,
                attempt,
                willRetry,
            )
            status.value >= 400 -> logger.warn(
                "Telegram API {} status={} durationMs={} attempt={} willRetry={}",
                methodName,
                status.value,
                durationMs,
                attempt,
                willRetry,
            )
            else -> logger.info(
                "Telegram API {} status={} durationMs={} attempt={} willRetry={}",
                methodName,
                status.value,
                durationMs,
                attempt,
                willRetry,
            )
        }
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    object AllowedUpdates {
        const val MESSAGE = "message"
        const val PRE_CHECKOUT_QUERY = "pre_checkout_query"
        const val SUCCESSFUL_PAYMENT = "successful_payment"
        val DEFAULT: List<String> = listOf(MESSAGE, PRE_CHECKOUT_QUERY, SUCCESSFUL_PAYMENT)
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 200L
        private const val MAX_RETRY_DELAY_MS = 800L
        private const val MAX_ERROR_BODY_PREVIEW = 512
        private const val NANOS_IN_MILLISECOND = 1_000_000L
    }
}

class TelegramApiException(message: String) : RuntimeException(message)

@Serializable
private data class SetWebhookRequest(
    val url: String,
    val secret_token: String,
    val allowed_updates: List<String>? = null,
    val max_connections: Int? = null,
)

@Serializable
private data class DeleteWebhookRequest(
    val drop_pending_updates: Boolean,
)

@Serializable
private data class GetUpdatesRequest(
    val offset: Long?,
    val timeout: Int,
    val allowed_updates: List<String>? = null,
)
