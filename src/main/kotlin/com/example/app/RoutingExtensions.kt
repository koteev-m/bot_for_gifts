package com.example.app

import com.example.app.api.errorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

internal fun Route.registerMiniAppRoutes(
    miniAppRoot: File?,
    miniAppIndex: File?,
) {
    if (miniAppRoot != null && miniAppIndex != null) {
        get("/app") {
            call.respondFile(miniAppIndex)
        }
        staticFiles("/app", miniAppRoot)
    } else {
        get("/app") {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                errorResponse(
                    status = HttpStatusCode.ServiceUnavailable,
                    reason = "Mini app bundle is not available",
                    message = "Build the frontend via `npm ci && npm run build` before starting the server.",
                    callId = call.callId,
                ),
            )
        }
    }
}
