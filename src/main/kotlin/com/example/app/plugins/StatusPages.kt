package com.example.app.plugins

import com.example.app.api.errorResponse
import com.example.app.logging.applicationLogger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            applicationLogger.error(
                "Bad request for {} {} (requestId={})",
                call.request.httpMethod.value,
                call.request.uri,
                call.callId ?: "-",
                cause,
            )
            val reason =
                cause.message?.takeUnless { it.isBlank() }
                    ?: HttpStatusCode.BadRequest.description
            call.respond(
                HttpStatusCode.BadRequest,
                errorResponse(
                    status = HttpStatusCode.BadRequest,
                    reason = reason,
                    callId = call.callId,
                ),
            )
        }

        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                errorResponse(
                    status = status,
                    reason = "Resource not found",
                    callId = call.callId,
                ),
            )
        }

        exception<Throwable> { call, cause ->
            applicationLogger.error(
                "Unhandled exception for {} {} (requestId={})",
                call.request.httpMethod.value,
                call.request.uri,
                call.callId ?: "-",
                cause,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                errorResponse(
                    status = HttpStatusCode.InternalServerError,
                    reason = "Internal server error",
                    callId = call.callId,
                ),
            )
        }
    }
}
