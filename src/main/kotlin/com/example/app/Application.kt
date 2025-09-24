package com.example.app

import com.example.app.economy.installEconomyIntegration
import com.example.app.logging.applicationLogger
import com.example.app.plugins.installCallIdPlugin
import com.example.app.plugins.installDefaultSecurityHeaders
import com.example.app.plugins.installJsonSerialization
import com.example.app.plugins.installMicrometerMetrics
import com.example.app.plugins.installRequestLogging
import com.example.app.plugins.installStatusPages
import com.example.app.routes.infrastructureRoutes
import com.example.app.telegram.installTelegramIntegration
import com.example.app.util.configValue
import com.typesafe.config.ConfigFactory
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.routing.routing
import java.io.File

fun main() {
    val environment =
        applicationEnvironment {
            log = applicationLogger
            config = HoconApplicationConfig(ConfigFactory.load())
        }

    embeddedServer(Netty, environment = environment) {
        module()
    }.start(wait = true)
}

@Suppress("unused")
fun Application.module() {
    installDefaultSecurityHeaders()
    installJsonSerialization()
    install(ConditionalHeaders)
    install(DoubleReceive)
    installCallIdPlugin()
    val prometheusRegistry = installMicrometerMetrics()
    installRequestLogging()
    installStatusPages()

    val config = environment.config
    val healthPath = config.propertyOrNull("app.healthPath")?.getString()?.takeUnless { it.isBlank() } ?: "/health"
    val metricsPath = config.propertyOrNull("app.metricsPath")?.getString()?.takeUnless { it.isBlank() } ?: "/metrics"

    val configuredMiniAppPath =
        configValue(
            propertyKeys = listOf("miniapp.dist"),
            envKeys = listOf("MINIAPP_DIST"),
            configKeys = listOf("app.miniapp.dist"),
        )?.takeUnless { it.isBlank() }

    val miniAppRoot =
        sequenceOf(configuredMiniAppPath, "miniapp/dist")
            .filterNotNull()
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?.absoluteFile
    val miniAppIndex = miniAppRoot?.resolve("index.html")?.takeIf { it.isFile }

    if (miniAppRoot == null || miniAppIndex == null) {
        applicationLogger.warn(
            "Mini app bundle is not available. Build the frontend via `npm ci && npm run build`.",
        )
    }

    installEconomyIntegration(meterRegistry = prometheusRegistry)
    installTelegramIntegration(meterRegistry = prometheusRegistry)

    routing {
        infrastructureRoutes(healthPath, metricsPath, prometheusRegistry)
        registerMiniAppRoutes(miniAppRoot, miniAppIndex)
    }
}
