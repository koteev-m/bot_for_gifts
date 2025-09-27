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
import java.security.SecureRandom
import java.util.Base64

private const val NONCE_BYTES: Int = 16
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
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
}
