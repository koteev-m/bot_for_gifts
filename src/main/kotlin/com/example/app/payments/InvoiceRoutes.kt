package com.example.app.payments

import com.example.app.api.errorResponse
import com.example.app.webapp.WebAppAuth
import com.example.app.webapp.WebAppAuthPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
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
