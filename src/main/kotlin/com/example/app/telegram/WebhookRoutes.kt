package com.example.app.telegram

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.telegram.dto.UpdateDto
import com.example.giftsbot.antifraud.PaymentsHardening
import com.example.giftsbot.antifraud.extractClientIp
import com.example.giftsbot.antifraud.velocity.AfEventType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

fun Route.telegramWebhookRoutes(
    webhookPath: String,
    expectedSecretToken: String,
    sink: UpdateSink,
    maxBodyBytes: Long = 1_000_000,
    meterRegistry: MeterRegistry? = null,
) {
    require(webhookPath.isNotBlank()) { "webhookPath must not be blank" }

    val metrics = WebhookMetrics(meterRegistry)

    route(webhookPath) {
        install(RequestBodyLimit) {
            bodyLimit { maxBodyBytes }
        }

        post {
            if (!validateContentType(call, metrics)) {
                return@post
            }
            if (!validateSecret(call, expectedSecretToken, metrics)) {
                return@post
            }

            val rawBody =
                try {
                    call.receiveText()
                } catch (_: PayloadTooLargeException) {
                    metrics.markTooLarge()
                    logBodyTooLarge(call, maxBodyBytes)
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        errorResponse("payload too large", HttpStatusCode.PayloadTooLarge),
                    )
                    return@post
                }
            val updates =
                try {
                    parseUpdates(rawBody)
                } catch (exception: BadRequestException) {
                    logInvalidJson(call, rawBody.length, exception)
                    call.respond(
                        HttpStatusCode.BadRequest,
                        errorResponse("invalid update json", HttpStatusCode.BadRequest),
                    )
                    return@post
                }

            metrics.markAccepted(updates.size)
            applyWebhookAntifraud(call, webhookPath, updates)
            logAcceptedUpdates(call, updates)
            enqueueUpdates(call, sink, updates, metrics)
            call.respondText("ok")
        }
    }
}

private suspend fun applyWebhookAntifraud(
    call: ApplicationCall,
    webhookPath: String,
    updates: List<UpdateDto>,
) {
    if (updates.isEmpty()) {
        return
    }
    val context = PaymentsHardening.context(call.application) ?: return
    val ip = extractClientIp(call, context.trustProxy)
    val userAgent = call.request.header(HttpHeaders.UserAgent)
    val velocity = context.velocityChecker
    val velocityActive = context.velocityEnabled && velocity != null
    for (update in updates) {
        val subjectId = update.message?.from?.id ?: update.pre_checkout_query?.from?.id
        val needsContext = update.pre_checkout_query != null || update.message?.successful_payment != null
        if (needsContext) {
            PaymentsHardening.rememberUpdateContext(
                updateId = update.update_id,
                call = call,
                ip = ip,
                subjectId = subjectId,
                userAgent = userAgent,
            )
        }
        if (velocityActive) {
            val checker = velocity ?: continue
            PaymentsHardening.checkAndMaybeAutoban(
                call = call,
                eventType = AfEventType.WEBHOOK,
                ip = ip,
                subjectId = subjectId,
                path = webhookPath,
                ua = userAgent,
                velocity = checker,
                suspiciousStore = context.suspiciousIpStore,
                meterRegistry = context.meterRegistry,
                autobanEnabled = context.autobanEnabled,
                autobanScore = context.autobanScore,
                autobanTtlSeconds = context.autobanTtlSeconds,
            )
        }
    }
}

private suspend fun validateSecret(
    call: ApplicationCall,
    expected: String,
    metrics: WebhookMetrics,
): Boolean {
    val providedSecret = call.request.header(TELEGRAM_SECRET_HEADER)
    if (providedSecret == expected) {
        return true
    }
    metrics.markRejected()
    logger.warn(
        "webhook rejected: callId={} reason=forbidden",
        call.callId ?: "-",
    )
    call.respond(
        HttpStatusCode.Forbidden,
        errorResponse("forbidden", HttpStatusCode.Forbidden),
    )
    return false
}

private suspend fun validateContentType(
    call: ApplicationCall,
    metrics: WebhookMetrics,
): Boolean {
    val rawContentType = call.request.headers[HttpHeaders.ContentType]
    val headerValue = rawContentType?.trim().orEmpty()
    val normalizedContentType = headerValue.substringBefore(';').trim()
    val isAllowed =
        headerValue.isEmpty() ||
            normalizedContentType.equals(JSON_CONTENT_TYPE, ignoreCase = true)
    if (isAllowed) {
        return true
    }
    metrics.markRejected()
    logger.warn(
        "webhook rejected: callId={} reason=unsupported_content_type",
        call.callId ?: "-",
    )
    call.respond(
        HttpStatusCode.UnsupportedMediaType,
        errorResponse("unsupported media type", HttpStatusCode.UnsupportedMediaType),
    )
    return false
}

private fun parseUpdates(raw: String): List<UpdateDto> {
    val payload = raw.trim()
    if (payload.isEmpty()) {
        throw BadRequestException("Request body is empty")
    }
    val singleResult = runCatching { json.decodeFromString(UpdateDto.serializer(), payload) }
    singleResult.getOrNull()?.let { return listOf(it) }
    return runCatching { json.decodeFromString(updatesListSerializer, payload) }
        .getOrElse { listError ->
            singleResult.exceptionOrNull()?.let(listError::addSuppressed)
            throw BadRequestException("Invalid update JSON", listError)
        }
}

private fun logAcceptedUpdates(
    call: ApplicationCall,
    updates: List<UpdateDto>,
) {
    val firstId = updates.firstOrNull()?.update_id ?: -1L
    val lastId = updates.lastOrNull()?.update_id ?: -1L
    logger.info(
        "webhook accepted updates: callId={} count={} firstId={} lastId={}",
        call.callId ?: "-",
        updates.size,
        if (firstId >= 0) firstId else "-",
        if (lastId >= 0) lastId else "-",
    )
}

private fun enqueueUpdates(
    call: ApplicationCall,
    sink: UpdateSink,
    updates: List<UpdateDto>,
    metrics: WebhookMetrics,
) {
    if (updates.isEmpty()) {
        metrics.recordEnqueue(0)
        return
    }
    val callIdValue = call.callId ?: "-"
    call.application.launch {
        val startNanos = System.nanoTime()
        try {
            for (update in updates) {
                runCatching { sink.enqueue(update) }
                    .onFailure { exception ->
                        if (exception is CancellationException) {
                            throw exception
                        }
                        logger.error(
                            "webhook enqueue failed: callId={} updateId={}",
                            callIdValue,
                            update.update_id,
                            exception,
                        )
                    }
            }
        } finally {
            val elapsed = System.nanoTime() - startNanos
            metrics.recordEnqueue(elapsed)
        }
    }
}

private fun logInvalidJson(
    call: ApplicationCall,
    bodyLength: Int,
    exception: BadRequestException,
) {
    logger.warn(
        "webhook invalid json: callId={} bodySize={} message={}",
        call.callId ?: "-",
        bodyLength,
        exception.message,
    )
}

private fun logBodyTooLarge(
    call: ApplicationCall,
    limitBytes: Long,
) {
    logger.warn(
        "webhook payload too large: callId={} limitBytes={}",
        call.callId ?: "-",
        limitBytes,
    )
}

private fun errorResponse(
    error: String,
    status: HttpStatusCode,
): Map<String, Any> =
    mapOf(
        "error" to error,
        "status" to status.value,
    )

private class WebhookMetrics(
    registry: MeterRegistry?,
) {
    private val componentTag = MetricsTags.COMPONENT to COMPONENT_VALUE
    private val updatesCounter =
        registry?.let { Metrics.counter(it, MetricsNames.WEBHOOK_UPDATES_TOTAL, componentTag) }
    private val rejectedCounter =
        registry?.let { Metrics.counter(it, MetricsNames.WEBHOOK_REJECTED_TOTAL, componentTag) }
    private val tooLargeCounter =
        registry?.let { Metrics.counter(it, MetricsNames.WEBHOOK_TOO_LARGE_TOTAL, componentTag) }
    private val enqueueTimer =
        registry?.let { Metrics.timer(it, MetricsNames.WEBHOOK_ENQUEUE_SECONDS, componentTag) }

    fun markAccepted(count: Int) {
        if (count > 0) {
            updatesCounter?.increment(count.toDouble())
        }
    }

    fun markRejected() {
        rejectedCounter?.increment()
    }

    fun markTooLarge() {
        tooLargeCounter?.increment()
    }

    fun recordEnqueue(durationNanos: Long) {
        if (durationNanos >= 0) {
            enqueueTimer?.record(durationNanos, TimeUnit.NANOSECONDS)
        }
    }
}

private const val TELEGRAM_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
private const val JSON_CONTENT_TYPE = "application/json"
private const val COMPONENT_VALUE = "webhook"

private val logger = LoggerFactory.getLogger("TelegramWebhookRoutes")
private val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
private val updatesListSerializer = ListSerializer(UpdateDto.serializer())
