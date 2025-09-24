package com.example.giftsbot.economy

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val ADMIN_HEADER = "X-Admin-Token"
private val logger = LoggerFactory.getLogger("EconomyRoutes")

fun Route.economyRoutes(
    repo: CasesRepository,
    adminToken: String?,
) {
    get("/api/miniapp/cases") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(repo.listPublic())
    }

    val token = adminToken?.takeUnless { it.isBlank() }
    if (token == null) {
        logger.warn("ADMIN_TOKEN is not configured. Economy admin routes will not be registered.")
        return
    }

    route("/internal/economy") {
        get("/preview") {
            if (!call.ensureAdmin(token)) {
                return@get
            }
            val caseId = call.caseIdOrNull() ?: return@get
            val preview = repo.getPreview(caseId)
            if (preview == null) {
                call.respondError(HttpStatusCode.NotFound, "case_not_found")
                return@get
            }
            call.respond(preview)
        }

        post("/reload") {
            if (!call.ensureAdmin(token)) {
                return@post
            }
            val summary =
                runCatching { repo.reload() }
                    .getOrElse { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        logger.error(
                            "Failed to reload cases via admin API: callId={} uri={}",
                            call.callId ?: "-",
                            call.request.uri,
                            error,
                        )
                        call.respondError(HttpStatusCode.InternalServerError, "reload_failed")
                        return@post
                    }
            call.respond(summary)
        }
    }
}

private suspend fun ApplicationCall.ensureAdmin(expected: String): Boolean {
    val provided = request.header(ADMIN_HEADER)?.takeUnless { it.isBlank() }
    return when {
        provided == null -> {
            logger.warn("Economy admin request missing token: callId={} uri={}", callId ?: "-", request.uri)
            respondError(HttpStatusCode.Unauthorized, "missing_admin_token")
            false
        }

        provided != expected -> {
            logger.warn("Economy admin request invalid token: callId={} uri={}", callId ?: "-", request.uri)
            respondError(HttpStatusCode.Forbidden, "invalid_admin_token")
            false
        }

        else -> true
    }
}

private suspend fun ApplicationCall.caseIdOrNull(): String? {
    val caseId = request.queryParameters["caseId"]?.trim()?.takeUnless { it.isEmpty() }
    if (caseId == null) {
        respondError(HttpStatusCode.BadRequest, "invalid_case_id")
    }
    return caseId
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    error: String,
) {
    respond(status, EconomyErrorResponse(error = error, status = status.value, requestId = callId))
}

@Serializable
private data class EconomyErrorResponse(
    val error: String,
    val status: Int,
    val requestId: String?,
)
