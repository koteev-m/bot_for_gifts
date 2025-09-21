package com.example.app.telegram

import com.example.giftsbot.telegram.TelegramApiClient
import com.example.giftsbot.telegram.WebhookInfoDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private const val ADMIN_TOKEN_HEADER = "X-Admin-Token"
private val logger = LoggerFactory.getLogger("AdminTelegramWebhookRoutes")

@Suppress("LongParameterList")
fun Route.adminTelegramWebhookRoutes(
    adminToken: String,
    telegramApiClient: TelegramApiClient,
    publicBaseUrl: String,
    webhookPath: String,
    webhookSecretToken: String,
    meterRegistry: MeterRegistry? = null,
) {
    val context =
        AdminWebhookContext(
            adminToken = adminToken,
            telegramApiClient = telegramApiClient,
            publicBaseUrl = publicBaseUrl,
            webhookPath = webhookPath,
            webhookSecretToken = webhookSecretToken,
            metrics = AdminWebhookMetrics(meterRegistry),
        )

    route("/internal/telegram/webhook") {
        post("/set") { context.handleSet(call) }
        post("/delete") { context.handleDelete(call) }
        get("/info") { context.handleInfo(call) }
    }
}

private class AdminWebhookContext(
    private val adminToken: String,
    private val telegramApiClient: TelegramApiClient,
    private val publicBaseUrl: String,
    private val webhookPath: String,
    private val webhookSecretToken: String,
    private val metrics: AdminWebhookMetrics,
) {
    suspend fun handleSet(call: ApplicationCall) {
        if (!call.ensureAdminToken(adminToken)) {
            return
        }
        val request = call.receiveSetWebhookRequest() ?: return
        when (val validation = validateSetRequest(request)) {
            is SetWebhookValidation.Error -> call.respondAdminError(validation.status, validation.message)
            is SetWebhookValidation.Success -> processSetWebhook(call, request, validation)
        }
    }

    suspend fun handleDelete(call: ApplicationCall) {
        if (!call.ensureAdminToken(adminToken)) {
            return
        }
        val dropPendingResult = parseDropPending(call.request.queryParameters["dropPending"])
        if (dropPendingResult == null) {
            call.respondAdminError(HttpStatusCode.BadRequest, "invalid_drop_pending")
            return
        }
        executeDeleteWebhook(call, dropPendingResult)
    }

    suspend fun handleInfo(call: ApplicationCall) {
        if (!call.ensureAdminToken(adminToken)) {
            return
        }
        logger.info("get webhook info: callId={}", call.callId ?: "-")
        val infoResult = runCatching { telegramApiClient.getWebhookInfo() }
        val failure = infoResult.exceptionOrNull()
        if (failure == null) {
            metrics.markInfoSuccess()
            call.respond(AdminWebhookInfoResponse.from(infoResult.getOrThrow()))
            return
        }
        if (failure is CancellationException) {
            throw failure
        }
        metrics.markFailure()
        logger.error(
            "getWebhookInfo failed: callId={}",
            call.callId ?: "-",
            failure,
        )
        call.respondAdminError(HttpStatusCode.InternalServerError, "internal_error")
    }

    private fun validateSetRequest(request: AdminSetWebhookRequest): SetWebhookValidation {
        val maxConnections = request.maxConnections
        if (maxConnections != null && maxConnections !in MIN_CONNECTIONS..MAX_CONNECTIONS) {
            return SetWebhookValidation.Error(HttpStatusCode.BadRequest, "invalid_max_connections")
        }
        val webhookUrlResult =
            runCatching { resolveWebhookUrl(request.url, publicBaseUrl, webhookPath) }
        val webhookUrl = webhookUrlResult.getOrNull()
        val errorMessage = webhookUrlResult.exceptionOrNull()?.message ?: "invalid_url"
        return if (webhookUrl == null) {
            SetWebhookValidation.Error(HttpStatusCode.BadRequest, errorMessage)
        } else {
            SetWebhookValidation.Success(webhookUrl, maxConnections, request.dropPending ?: false)
        }
    }

    private suspend fun processSetWebhook(
        call: ApplicationCall,
        request: AdminSetWebhookRequest,
        params: SetWebhookValidation.Success,
    ) {
        logger.info(
            "set webhook: callId={} url={} dropPending={} maxConnections={} allowedUpdates={}",
            call.callId ?: "-",
            params.url,
            params.dropPending,
            params.maxConnections,
            request.allowedUpdates,
        )
        val apiResult =
            runCatching {
                telegramApiClient.setWebhook(
                    url = params.url,
                    secretToken = webhookSecretToken,
                    allowedUpdates = request.allowedUpdates,
                    maxConnections = params.maxConnections,
                    dropPendingUpdates = params.dropPending,
                )
            }
        val failure = apiResult.exceptionOrNull()
        if (failure == null) {
            metrics.markSetSuccess()
            call.respond(
                AdminSetWebhookResponse(
                    ok = true,
                    url = params.url,
                    allowedUpdates = request.allowedUpdates,
                    maxConnections = params.maxConnections,
                ),
            )
            return
        }
        if (failure is CancellationException) {
            throw failure
        }
        metrics.markFailure()
        logger.error(
            "setWebhook failed: callId={} url={}",
            call.callId ?: "-",
            params.url,
            failure,
        )
        call.respondAdminError(HttpStatusCode.InternalServerError, "internal_error")
    }

    private suspend fun executeDeleteWebhook(
        call: ApplicationCall,
        dropPending: Boolean,
    ) {
        logger.info(
            "delete webhook: callId={} dropPending={}",
            call.callId ?: "-",
            dropPending,
        )
        val apiResult = runCatching { telegramApiClient.deleteWebhook(dropPending) }
        val failure = apiResult.exceptionOrNull()
        if (failure == null) {
            metrics.markDeleteSuccess()
            call.respond(AdminDeleteWebhookResponse(ok = true, dropPending = dropPending))
            return
        }
        if (failure is CancellationException) {
            throw failure
        }
        metrics.markFailure()
        logger.error(
            "deleteWebhook failed: callId={} dropPending={}",
            call.callId ?: "-",
            dropPending,
            failure,
        )
        call.respondAdminError(HttpStatusCode.InternalServerError, "internal_error")
    }
}

private sealed interface SetWebhookValidation {
    data class Success(
        val url: String,
        val maxConnections: Int?,
        val dropPending: Boolean,
    ) : SetWebhookValidation

    data class Error(
        val status: HttpStatusCode,
        val message: String,
    ) : SetWebhookValidation
}

private class AdminWebhookMetrics(
    registry: MeterRegistry?,
) {
    private val setCounter = registry?.counter("admin_webhook_set_total")
    private val deleteCounter = registry?.counter("admin_webhook_delete_total")
    private val infoCounter = registry?.counter("admin_webhook_info_total")
    private val failCounter = registry?.counter("admin_webhook_fail_total")

    fun markSetSuccess() {
        setCounter?.increment()
    }

    fun markDeleteSuccess() {
        deleteCounter?.increment()
    }

    fun markInfoSuccess() {
        infoCounter?.increment()
    }

    fun markFailure() {
        failCounter?.increment()
    }
}

private suspend fun ApplicationCall.ensureAdminToken(expected: String): Boolean {
    val provided = request.header(ADMIN_TOKEN_HEADER)
    if (provided == expected) {
        return true
    }
    logger.warn(
        "Admin webhook unauthorized: callId={} method={} uri={} headerPresent={}",
        callId ?: "-",
        request.httpMethod.value,
        request.uri,
        provided != null,
    )
    respondAdminError(HttpStatusCode.Unauthorized, "unauthorized")
    return false
}

private suspend fun ApplicationCall.receiveSetWebhookRequest(): AdminSetWebhookRequest? =
    try {
        receive()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: ContentTransformationException) {
        logger.warn(
            "Invalid admin webhook request body: callId={} uri={}",
            callId ?: "-",
            request.uri,
            cause,
        )
        respondAdminError(HttpStatusCode.BadRequest, "invalid_request")
        null
    } catch (cause: SerializationException) {
        logger.warn(
            "Invalid admin webhook request body: callId={} uri={}",
            callId ?: "-",
            request.uri,
            cause,
        )
        respondAdminError(HttpStatusCode.BadRequest, "invalid_request")
        null
    }

private suspend fun ApplicationCall.respondAdminError(
    status: HttpStatusCode,
    error: String,
) {
    respond(status, AdminErrorResponse(error = error, status = status.value, requestId = callId))
}

private fun parseDropPending(rawValue: String?): Boolean? =
    when {
        rawValue == null -> false
        rawValue.equals("true", ignoreCase = true) -> true
        rawValue.equals("false", ignoreCase = true) -> false
        rawValue == "1" -> true
        rawValue == "0" -> false
        else -> null
    }

private fun resolveWebhookUrl(
    explicitUrl: String?,
    baseUrl: String,
    path: String,
): String {
    explicitUrl?.trim()?.takeUnless { it.isEmpty() }?.let { return it }

    val normalizedBase = baseUrl.trim().trimEnd('/')
    require(normalizedBase.isNotEmpty()) { "PUBLIC_BASE_URL must not be blank" }

    val trimmedPath = path.trim()
    require(trimmedPath.isNotEmpty()) { "WEBHOOK_PATH must not be blank" }

    val leadingSlashPath = if (trimmedPath.startsWith('/')) trimmedPath else "/$trimmedPath"
    return normalizedBase + leadingSlashPath
}

@Serializable
data class AdminSetWebhookRequest(
    val url: String? = null,
    val allowedUpdates: List<String>? = null,
    val maxConnections: Int? = null,
    val dropPending: Boolean? = null,
)

@Serializable
data class AdminSetWebhookResponse(
    val ok: Boolean,
    val url: String,
    val allowedUpdates: List<String>? = null,
    val maxConnections: Int? = null,
)

@Serializable
data class AdminDeleteWebhookResponse(
    val ok: Boolean,
    val dropPending: Boolean,
)

@Serializable
data class AdminWebhookInfoResponse(
    val url: String,
    val hasCustomCertificate: Boolean,
    val pendingUpdateCount: Int,
    val ipAddress: String? = null,
    val lastErrorDate: Int? = null,
    val lastErrorMessage: String? = null,
    val lastSynchronizationErrorDate: Int? = null,
    val maxConnections: Int? = null,
    val allowedUpdates: List<String>? = null,
) {
    companion object {
        fun from(dto: WebhookInfoDto): AdminWebhookInfoResponse =
            AdminWebhookInfoResponse(
                url = dto.url,
                hasCustomCertificate = dto.has_custom_certificate,
                pendingUpdateCount = dto.pending_update_count,
                ipAddress = dto.ip_address,
                lastErrorDate = dto.last_error_date,
                lastErrorMessage = dto.last_error_message,
                lastSynchronizationErrorDate = dto.last_synchronization_error_date,
                maxConnections = dto.max_connections,
                allowedUpdates = dto.allowed_updates,
            )
    }
}

@Serializable
data class AdminErrorResponse(
    val error: String,
    val status: Int,
    val requestId: String?,
)

private const val MIN_CONNECTIONS = 1
private const val MAX_CONNECTIONS = 100
