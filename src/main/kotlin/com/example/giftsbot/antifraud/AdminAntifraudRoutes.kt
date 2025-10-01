package com.example.giftsbot.antifraud

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private const val ADMIN_HEADER = "X-Admin-Token"
private const val TYPE_RECENT = "recent"
private const val TYPE_BANNED = "banned"
private const val ERROR_UNAUTHORIZED = "unauthorized"
private const val ERROR_FORBIDDEN = "forbidden"
private const val ERROR_INVALID_REQUEST = "invalid_request"
private const val ERROR_INVALID_IP = "invalid_ip"
private const val ERROR_INVALID_TTL = "invalid_ttl"
private const val ERROR_INVALID_TYPE = "invalid_type"
private const val ERROR_INVALID_LIMIT = "invalid_limit"
private const val ERROR_INVALID_SINCE = "invalid_since"
private const val DEFAULT_LIST_LIMIT = 100
private const val MIN_LIST_LIMIT = 1
private const val MAX_LIST_LIMIT = 100
private const val MIN_TTL_SECONDS = 0L
private val logger = LoggerFactory.getLogger("AdminAntifraudRoutes")

fun Route.adminAntifraudRoutes(
    adminToken: String,
    store: SuspiciousIpStore,
    meterRegistry: MeterRegistry,
    defaultBanTtlSeconds: Long,
) {
    val handler =
        AdminAntifraudHandler(
            adminToken = adminToken,
            store = store,
            metrics = AdminAntifraudMetrics(meterRegistry),
            defaultBanTtlSeconds = defaultBanTtlSeconds,
        )
    route("/internal/antifraud/ip") {
        post("/mark-suspicious") { handler.handleMarkSuspicious(call) }
        post("/ban") { handler.handleBan(call) }
        post("/unban") { handler.handleUnban(call) }
        get("/list") { handler.handleList(call) }
    }
}

private class AdminAntifraudHandler(
    private val adminToken: String,
    private val store: SuspiciousIpStore,
    private val metrics: AdminAntifraudMetrics,
    private val defaultBanTtlSeconds: Long,
) {
    suspend fun handleMarkSuspicious(call: ApplicationCall) {
        if (!call.ensureAdminToken(adminToken)) {
            return
        }
        val request = call.receivePayload<MarkSuspiciousRequest>()
        if (request == null) {
            return
        }
        val ip = request.ip.trim()
        if (ip.isNotEmpty()) {
            val entry = store.markSuspicious(ip, request.reason)
            metrics.markSuspicious()
            logger.info("suspicious mark success: requestId={}", call.callId ?: "-")
            call.respond(entry)
        } else {
            call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_IP)
        }
    }

    suspend fun handleBan(call: ApplicationCall) {
        if (!call.ensureAdminToken(adminToken)) {
            return
        }
        val request = call.receivePayload<BanIpRequest>()
        if (request == null) {
            return
        }
        val ip = request.ip.trim()
        val ttl = request.ttlSeconds ?: defaultBanTtlSeconds
        when {
            ip.isEmpty() -> call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_IP)
            ttl < MIN_TTL_SECONDS -> call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_TTL)
            else -> {
                val entry = store.ban(ip, ttl, request.reason)
                metrics.markBan(entry.status)
                logger.info("ban success: requestId={} status={}", call.callId ?: "-", entry.status)
                call.respond(entry)
            }
        }
    }

    suspend fun handleUnban(call: ApplicationCall) {
        if (!call.ensureAdminToken(adminToken)) {
            return
        }
        val request = call.receivePayload<UnbanIpRequest>()
        if (request == null) {
            return
        }
        val ip = request.ip.trim()
        if (ip.isNotEmpty()) {
            val removed = store.unban(ip)
            if (removed) {
                metrics.markUnban()
            }
            logger.info("unban processed: requestId={} removed={}", call.callId ?: "-", removed)
            call.respond(UnbanIpResponse(ok = removed))
        } else {
            call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_IP)
        }
    }

    suspend fun handleList(call: ApplicationCall) {
        if (!call.ensureAdminToken(adminToken)) {
            return
        }
        val type = call.request.queryParameters["type"]?.lowercase() ?: TYPE_RECENT
        val limit = resolveLimit(call) ?: return
        val now = System.currentTimeMillis()
        val response =
            when (type) {
                TYPE_RECENT -> {
                    val sinceParam = resolveSince(call)
                    if (sinceParam.valid) {
                        store.listRecent(limit = limit, sinceMs = sinceParam.value, nowMs = now)
                    } else {
                        null
                    }
                }
                TYPE_BANNED -> store.listBanned(limit = limit, nowMs = now)
                else -> {
                    call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_TYPE)
                    null
                }
            }
        if (response != null) {
            logger.info("list success: requestId={} type={}", call.callId ?: "-", type)
            call.respond(response)
        }
    }

    private suspend fun resolveLimit(call: ApplicationCall): Int? {
        val rawLimit = call.request.queryParameters["limit"]
        if (rawLimit == null) {
            return DEFAULT_LIST_LIMIT
        }
        val parsed = rawLimit.toIntOrNull()
        return if (parsed != null && parsed in MIN_LIST_LIMIT..MAX_LIST_LIMIT) {
            parsed
        } else {
            call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_LIMIT)
            null
        }
    }

    private suspend fun resolveSince(call: ApplicationCall): SinceParam {
        val rawSince = call.request.queryParameters["sinceMs"]
        return if (rawSince == null) {
            SinceParam(value = null, valid = true)
        } else {
            val parsed = rawSince.toLongOrNull()
            if (parsed == null || parsed < 0) {
                call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_SINCE)
                SinceParam(value = null, valid = false)
            } else {
                SinceParam(value = parsed, valid = true)
            }
        }
    }
}

private data class SinceParam(
    val value: Long?,
    val valid: Boolean,
)

private class AdminAntifraudMetrics(
    registry: MeterRegistry,
) {
    private val markCounter: Counter = Counter.builder("af_ip_suspicious_mark_total").register(registry)
    private val banTempCounter: Counter =
        Counter
            .builder("af_ip_ban_total")
            .tag("type", "temp")
            .register(registry)
    private val banPermCounter: Counter =
        Counter
            .builder("af_ip_ban_total")
            .tag("type", "perm")
            .register(registry)
    private val unbanCounter: Counter = Counter.builder("af_ip_unban_total").register(registry)

    fun markSuspicious() {
        markCounter.increment()
    }

    fun markBan(status: IpStatus) {
        when (status) {
            IpStatus.TEMP_BANNED -> banTempCounter.increment()
            IpStatus.PERM_BANNED -> banPermCounter.increment()
            IpStatus.SUSPICIOUS -> {}
        }
    }

    fun markUnban() {
        unbanCounter.increment()
    }
}

private suspend fun ApplicationCall.ensureAdminToken(adminToken: String): Boolean {
    val provided = request.header(ADMIN_HEADER)
    return when {
        provided == null -> {
            logger.warn("admin auth missing: requestId={}", callId ?: "-")
            respondError(HttpStatusCode.Unauthorized, ERROR_UNAUTHORIZED)
            false
        }
        provided != adminToken -> {
            logger.warn("admin auth mismatch: requestId={}", callId ?: "-")
            respondError(HttpStatusCode.Forbidden, ERROR_FORBIDDEN)
            false
        }
        else -> true
    }
}

private suspend inline fun <reified T> ApplicationCall.receivePayload(): T? =
    try {
        receive()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: ContentTransformationException) {
        logger.warn("invalid admin request body: requestId={} uri={}", callId ?: "-", request.uri, cause)
        respondError(HttpStatusCode.BadRequest, ERROR_INVALID_REQUEST)
        null
    } catch (cause: SerializationException) {
        logger.warn("invalid admin request body: requestId={} uri={}", callId ?: "-", request.uri, cause)
        respondError(HttpStatusCode.BadRequest, ERROR_INVALID_REQUEST)
        null
    }

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    error: String,
) {
    respond(
        status,
        AdminAntifraudErrorResponse(
            error = error,
            status = status.value,
            requestId = callId,
        ),
    )
}

@Serializable
data class MarkSuspiciousRequest(
    val ip: String,
    val reason: String? = null,
)

@Serializable
data class BanIpRequest(
    val ip: String,
    val ttlSeconds: Long? = null,
    val reason: String? = null,
)

@Serializable
data class UnbanIpRequest(
    val ip: String,
)

@Serializable
data class UnbanIpResponse(
    val ok: Boolean,
)

@Serializable
data class AdminAntifraudErrorResponse(
    val error: String,
    val status: Int,
    val requestId: String?,
)
