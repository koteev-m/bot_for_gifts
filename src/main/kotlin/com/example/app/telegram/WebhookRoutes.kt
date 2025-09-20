package com.example.app.telegram

import com.example.app.telegram.dto.UpdateDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Route.telegramWebhookRoutes(
    webhookPath: String,
    expectedSecretToken: String,
    sink: UpdateSink,
    maxBodyBytes: Long = 1_000_000,
) {
    require(webhookPath.isNotBlank()) { "webhookPath must not be blank" }

    route(webhookPath) {
        install(RequestBodyLimit) {
            bodyLimit { maxBodyBytes }
        }

        post {
            if (!validateContentType(call)) {
                return@post
            }
            if (!validateSecret(call, expectedSecretToken)) {
                return@post
            }

            val rawBody = call.receiveText()
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

            logAcceptedUpdates(call, updates)
            enqueueUpdates(call, sink, updates)
            call.respondText("ok")
        }
    }
}

private suspend fun validateSecret(
    call: ApplicationCall,
    expected: String,
): Boolean {
    val providedSecret = call.request.header(TELEGRAM_SECRET_HEADER)
    if (providedSecret == expected) {
        return true
    }
    val callIdValue = call.callId ?: "-"
    logger.warn(
        "Telegram webhook forbidden: callId={} method={} path={} headerPresent={}",
        callIdValue,
        call.request.httpMethod.value,
        call.request.path(),
        providedSecret != null,
    )
    call.respond(
        HttpStatusCode.Forbidden,
        errorResponse("forbidden", HttpStatusCode.Forbidden),
    )
    return false
}

private suspend fun validateContentType(call: ApplicationCall): Boolean {
    val rawContentType = call.request.headers[HttpHeaders.ContentType]
    val headerValue = rawContentType?.trim().orEmpty()
    val normalizedContentType = headerValue.substringBefore(';').trim()
    val isAllowed =
        headerValue.isEmpty() ||
            normalizedContentType.equals(JSON_CONTENT_TYPE, ignoreCase = true)
    if (isAllowed) {
        return true
    }
    val callIdValue = call.callId ?: "-"
    logger.warn(
        "Telegram webhook unsupported content type: callId={} method={} path={} value={}",
        callIdValue,
        call.request.httpMethod.value,
        call.request.path(),
        headerValue,
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
    val suffixes =
        if (updates.isEmpty()) {
            "none"
        } else {
            updates.joinToString(separator = ",") { update ->
                update.update_id.toString().takeLast(SHORT_SUFFIX_LENGTH)
            }
        }
    logger.info(
        "Telegram webhook accepted: callId={} method={} path={} updates={} suffixes={}",
        call.callId ?: "-",
        call.request.httpMethod.value,
        call.request.path(),
        updates.size,
        suffixes,
    )
}

@Suppress("SwallowedException", "Detekt.SwallowedException")
private fun enqueueUpdates(
    call: ApplicationCall,
    sink: UpdateSink,
    updates: List<UpdateDto>,
) {
    val callIdValue = call.callId ?: "-"
    call.application.launch {
        for (update in updates) {
            runCatching {
                sink.enqueue(update)
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                logger.error(
                    "Failed to enqueue Telegram update: callId={} suffix={}",
                    callIdValue,
                    update.update_id.toString().takeLast(SHORT_SUFFIX_LENGTH),
                    exception,
                )
            }
        }
    }
}

private fun logInvalidJson(
    call: ApplicationCall,
    bodyLength: Int,
    exception: BadRequestException,
) {
    logger.warn(
        "Telegram webhook invalid JSON: callId={} method={} path={} bodySize={}",
        call.callId ?: "-",
        call.request.httpMethod.value,
        call.request.path(),
        bodyLength,
        exception,
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

private const val TELEGRAM_SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
private const val SHORT_SUFFIX_LENGTH = 3
private const val JSON_CONTENT_TYPE = "application/json"

private val logger = LoggerFactory.getLogger("TelegramWebhookRoutes")
private val json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
private val updatesListSerializer = ListSerializer(UpdateDto.serializer())
