package com.example.app.routes

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable

fun Route.infrastructureRoutes(
    healthPath: String,
    metricsPath: String,
    prometheus: PrometheusMeterRegistry,
) {
    get(healthPath) {
        call.respondText("OK", contentType = ContentType.Text.Plain)
    }

    get(metricsPath) {
        call.respondText(
            text = prometheus.scrape(),
            contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
        )
    }

    get("/version") {
        call.respond(versionInfo())
    }
}

private fun versionInfo(): VersionInfo =
    VersionInfo(
        app =
            propertyOrDefault(
                "app",
                "app.name",
                defaultValue = "gifts-bot",
            ),
        version =
            propertyOrDefault(
                "version",
                "app.version",
                defaultValue = "dev",
            ),
        git =
            propertyOrDefault(
                "git",
                "git.commit",
                "git.sha",
                defaultValue = "unknown",
            ),
        buildTime =
            propertyOrDefault(
                "buildTime",
                "build.time",
                defaultValue = "unknown",
            ),
    )

private fun propertyOrDefault(
    vararg keys: String,
    defaultValue: String,
): String {
    val value =
        keys
            .asSequence()
            .mapNotNull { key -> System.getProperty(key)?.takeUnless { it.isBlank() } }
            .firstOrNull()
    return value ?: defaultValue
}

@Serializable
private data class VersionInfo(
    val app: String,
    val version: String,
    val git: String,
    val buildTime: String,
)
