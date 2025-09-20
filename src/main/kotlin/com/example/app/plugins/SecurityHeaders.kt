package com.example.app.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders

fun Application.installDefaultSecurityHeaders() {
    install(DefaultHeaders) {
        header(name = "Server", value = "gifts-bot")
        header(name = "X-Content-Type-Options", value = "nosniff")
        header(name = "X-Frame-Options", value = "DENY")
        header(name = "Referrer-Policy", value = "no-referrer")
    }
}
