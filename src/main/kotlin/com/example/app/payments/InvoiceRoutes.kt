package com.example.app.payments

import com.example.app.api.errorResponse
import com.example.app.webapp.WebAppAuth
import com.example.app.webapp.WebAppAuthPlugin
import com.example.giftsbot.antifraud.PaymentsHardening
import com.example.giftsbot.antifraud.extractClientIp
import com.example.giftsbot.antifraud.extractSubjectId
import com.example.giftsbot.antifraud.velocity.AfEventType
import com.example.giftsbot.antifraud.velocity.VelocityAction
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.SecureRandom

private const val NONCE_BYTES: Int = 12
private const val BASE62_ALPHABET: String = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private val BASE62_RADIX: BigInteger = BigInteger.valueOf(BASE62_ALPHABET.length.toLong())
private val secureRandom: SecureRandom = SecureRandom()

fun Route.registerMiniAppInvoiceRoutes(
    botToken: String,
    invoiceService: TelegramInvoiceService,
) {
    route("/api/miniapp") {
        install(WebAppAuthPlugin) {
            this.botToken = botToken
        }

        post("/invoice") {
            val request = call.receive<CreateMiniAppInvoiceRequest>()
            val caseId = request.caseId.trim()
            if (caseId.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    errorResponse(
                        status = HttpStatusCode.BadRequest,
                        reason = "invalid_case_id",
                        message = "Case ID must not be blank",
                        callId = call.callId,
                    ),
                )
                return@post
            }

            if (!call.applyInvoiceAntifraud()) {
                return@post
            }

            val userId = call.attributes[WebAppAuth.UserIdKey]
            val nonce = generateNonce()
            val response =
                invoiceService.createCaseInvoice(
                    caseId = caseId,
                    userId = userId,
                    nonce = nonce,
                )

            call.respond(response)
        }
    }
}

@Serializable
private data class CreateMiniAppInvoiceRequest(
    val caseId: String,
)

private fun generateNonce(): String {
    val buffer = ByteArray(NONCE_BYTES)
    secureRandom.nextBytes(buffer)
    var value = BigInteger(1, buffer)
    if (value == BigInteger.ZERO) {
        return BASE62_ALPHABET.first().toString()
    }

    val builder = StringBuilder()
    while (value > BigInteger.ZERO) {
        val division = value.divideAndRemainder(BASE62_RADIX)
        val remainder = division[1].toInt()
        builder.append(BASE62_ALPHABET[remainder])
        value = division[0]
    }

    return builder.reverse().toString()
}

private fun resolveSubjectFromWebApp(call: ApplicationCall): Long? =
    if (call.attributes.contains(WebAppAuth.UserIdKey)) {
        call.attributes[WebAppAuth.UserIdKey]
    } else {
        null
    }

private suspend fun ApplicationCall.applyInvoiceAntifraud(): Boolean {
    val context = PaymentsHardening.context(application)
    val velocity = context?.velocityChecker
    if (context == null || !context.velocityEnabled || velocity == null) {
        return true
    }
    val subjectId = extractSubjectId(this) ?: resolveSubjectFromWebApp(this)
    val outcome =
        PaymentsHardening.checkAndMaybeAutoban(
            call = this,
            eventType = AfEventType.INVOICE,
            ip = extractClientIp(this, context.trustProxy),
            subjectId = subjectId,
            path = "/api/miniapp/invoice",
            ua = request.header(HttpHeaders.UserAgent),
            velocity = velocity,
            suspiciousStore = context.suspiciousIpStore,
            meterRegistry = context.meterRegistry,
            autobanEnabled = context.autobanEnabled,
            autobanScore = context.autobanScore,
            autobanTtlSeconds = context.autobanTtlSeconds,
        )
    val blocked = outcome.decision.action == VelocityAction.HARD_BLOCK_BEFORE_PAYMENT
    if (blocked) {
        context
            .meterRegistry
            .counter("pay_af_blocks_total", "type", "invoice")
            .increment()
        PaymentsHardening.respondTooManyRequests(
            call = this,
            retryAfterSeconds = context.retryAfterSeconds,
            requestId = callId,
            type = "velocity",
        )
    }
    return !blocked
}
