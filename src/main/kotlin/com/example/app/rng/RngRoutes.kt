package com.example.app.rng

import com.example.app.observability.Metrics
import com.example.giftsbot.rng.LocalDateIso8601Serializer
import com.example.giftsbot.rng.RngCommitRevealed
import com.example.giftsbot.rng.RngService
import com.example.giftsbot.rng.RngVerificationOutcome
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
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
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.CancellationException

private const val ADMIN_HEADER = "X-Admin-Token"
private const val RNG_HTTP_METRIC = "rng_http_total"
private val logger = LoggerFactory.getLogger("RngRoutes")

fun Route.rngRoutes(
    service: RngService,
    meterRegistry: MeterRegistry,
    adminToken: String?,
) {
    val metrics = RngHttpMetrics(meterRegistry)
    val handler = RngRouteHandler(service, metrics)

    route("/fairness") {
        get("/today") {
            handler.fairnessToday(call)
        }

        get("/reveal/{day}") {
            handler.fairnessReveal(call)
        }

        post("/verify") {
            handler.fairnessVerify(call)
        }
    }

    val token = adminToken?.takeUnless { it.isBlank() }
    if (token == null) {
        logger.warn("ADMIN_TOKEN is not configured. RNG admin routes will not be registered.")
        return
    }

    route("/internal/rng") {
        post("/commit-today") {
            handler.commitToday(call, token)
        }

        post("/reveal") {
            handler.internalReveal(call, token)
        }
    }
}

private class RngRouteHandler(
    private val service: RngService,
    private val metrics: RngHttpMetrics,
) {
    suspend fun fairnessToday(call: ApplicationCall) {
        call.withMetrics("fairness_today") {
            val state = service.ensureTodayCommit()
            respond(
                FairnessTodayResponse(
                    dayUtc = state.dayUtc,
                    serverSeedHash = state.serverSeedHash,
                ),
            )
        }
    }

    suspend fun fairnessReveal(call: ApplicationCall) {
        val op = "fairness_reveal"
        val dayUtc = call.parseDay(call.parameters["day"], op) ?: return
        if (dayUtc.isBefore(service.currentDay)) {
            val revealed = call.tryReveal(dayUtc, op)
            if (revealed != null) {
                call.withMetrics(op) {
                    respond(revealed)
                }
            }
        } else {
            metrics.error(op)
            call.respondRngError(HttpStatusCode.BadRequest, "reveal_not_available")
        }
    }

    suspend fun fairnessVerify(call: ApplicationCall) {
        val op = "fairness_verify"
        val request = call.receiveVerifyRequest(op) ?: return
        val outcome =
            service.verify(
                dayUtc = request.dayUtc,
                serverSeed = request.serverSeed,
                userId = request.userId,
                nonce = request.nonce,
                caseId = request.caseId,
            )
        when (outcome) {
            RngVerificationOutcome.CommitMissing -> {
                metrics.error(op)
                call.respondRngError(HttpStatusCode.NotFound, "commit_not_found")
            }

            RngVerificationOutcome.InvalidServerSeed -> {
                metrics.error(op)
                call.respondRngError(HttpStatusCode.BadRequest, "invalid_server_seed")
            }

            RngVerificationOutcome.ServerSeedMismatch -> {
                metrics.error(op)
                call.respondRngError(HttpStatusCode.BadRequest, "server_seed_mismatch")
            }

            is RngVerificationOutcome.Success -> {
                val result = outcome.result
                call.withMetrics(op) {
                    respond(
                        FairnessVerifyResponse(
                            ppm = result.ppm,
                            rollHex = result.rollHex,
                            serverSeedHash = result.serverSeedHash,
                            valid = true,
                        ),
                    )
                }
            }
        }
    }

    suspend fun commitToday(
        call: ApplicationCall,
        adminToken: String,
    ) {
        val op = "internal_commit_today"
        if (!call.ensureAdmin(adminToken, op)) {
            return
        }

        call.withMetrics(op) {
            val state = service.ensureTodayCommit()
            respond(state)
        }
    }

    suspend fun internalReveal(
        call: ApplicationCall,
        adminToken: String,
    ) {
        val op = "internal_reveal"
        if (!call.ensureAdmin(adminToken, op)) {
            return
        }

        val dayUtc = call.parseDay(call.request.queryParameters["day"], op) ?: return
        val revealed = call.tryReveal(dayUtc, op)
        if (revealed != null) {
            call.withMetrics(op) {
                respond(revealed)
            }
        }
    }

    private suspend fun ApplicationCall.tryReveal(
        dayUtc: LocalDate,
        op: String,
    ): RngCommitRevealed? =
        try {
            service.reveal(dayUtc)
        } catch (_: IllegalStateException) {
            metrics.error(op)
            respondRngError(HttpStatusCode.NotFound, "commit_not_found")
            null
        } catch (cause: IllegalArgumentException) {
            metrics.error(op)
            throw cause
        }

    private suspend fun ApplicationCall.receiveVerifyRequest(op: String): FairnessVerifyRequest? =
        try {
            receive()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: ContentTransformationException) {
            metrics.error(op)
            logger.warn(
                "Invalid fairness verify payload: callId={} uri={}",
                callId ?: "-",
                request.uri,
                cause,
            )
            respondRngError(HttpStatusCode.BadRequest, "invalid_request")
            null
        } catch (cause: SerializationException) {
            metrics.error(op)
            logger.warn(
                "Invalid fairness verify payload: callId={} uri={}",
                callId ?: "-",
                request.uri,
                cause,
            )
            respondRngError(HttpStatusCode.BadRequest, "invalid_request")
            null
        }

    private suspend fun ApplicationCall.parseDay(
        rawValue: String?,
        op: String,
    ): LocalDate? {
        val value = rawValue?.trim()
        if (value.isNullOrEmpty()) {
            metrics.error(op)
            respondRngError(HttpStatusCode.BadRequest, "invalid_day")
            return null
        }

        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            metrics.error(op)
            respondRngError(HttpStatusCode.BadRequest, "invalid_day")
            null
        }
    }

    private suspend fun ApplicationCall.ensureAdmin(
        expected: String,
        op: String,
    ): Boolean {
        val provided = request.header(ADMIN_HEADER)?.takeUnless { it.isBlank() }
        return when {
            provided == null -> {
                metrics.error(op)
                logger.warn(
                    "RNG admin request missing token: callId={} uri={}",
                    callId ?: "-",
                    request.uri,
                )
                respondRngError(HttpStatusCode.Unauthorized, "missing_admin_token")
                false
            }

            provided != expected -> {
                metrics.error(op)
                logger.warn(
                    "RNG admin request invalid token: callId={} uri={}",
                    callId ?: "-",
                    request.uri,
                )
                respondRngError(HttpStatusCode.Forbidden, "invalid_admin_token")
                false
            }

            else -> true
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun ApplicationCall.withMetrics(
        op: String,
        block: suspend ApplicationCall.() -> Unit,
    ) {
        try {
            block()
            metrics.success(op)
        } catch (cause: Throwable) {
            metrics.error(op)
            throw cause
        }
    }
}

private suspend fun ApplicationCall.respondRngError(
    status: HttpStatusCode,
    error: String,
) {
    respond(status, RngErrorResponse(error = error, status = status.value, requestId = callId))
}

@Serializable
private data class FairnessTodayResponse(
    @Serializable(with = LocalDateIso8601Serializer::class)
    val dayUtc: LocalDate,
    val serverSeedHash: String,
)

@Serializable
private data class FairnessVerifyRequest(
    @Serializable(with = LocalDateIso8601Serializer::class)
    val dayUtc: LocalDate,
    val serverSeed: String,
    val userId: Long,
    val nonce: String,
    val caseId: String,
)

@Serializable
private data class FairnessVerifyResponse(
    val ppm: Int,
    val rollHex: String,
    val serverSeedHash: String,
    val valid: Boolean,
)

@Serializable
private data class RngErrorResponse(
    val error: String,
    val status: Int,
    val requestId: String?,
)

private class RngHttpMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun success(op: String) {
        increment(op, "success")
    }

    fun error(op: String) {
        increment(op, "error")
    }

    private fun increment(
        op: String,
        result: String,
    ) {
        Metrics
            .counter(
                meterRegistry,
                RNG_HTTP_METRIC,
                "op" to op,
                "result" to result,
            ).increment()
    }
}
