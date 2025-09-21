package com.example.app

import com.example.app.logging.applicationLogger
import com.example.app.miniapp.MiniCasesConfigService
import com.example.app.plugins.installCallIdPlugin
import com.example.app.plugins.installDefaultSecurityHeaders
import com.example.app.plugins.installJsonSerialization
import com.example.app.plugins.installMicrometerMetrics
import com.example.app.plugins.installRequestLogging
import com.example.app.plugins.installStatusPages
import com.example.app.routes.infrastructureRoutes
import com.example.app.telegram.adminTelegramWebhookRoutes
import com.example.app.util.configValue
import com.example.giftsbot.telegram.TelegramApiClient
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

    val telegramConfig = loadTelegramConfig()
    val telegramApiClient = TelegramApiClient(telegramConfig.botToken)

    val miniCasesConfigService = MiniCasesConfigService()

    routing {
        infrastructureRoutes(healthPath, metricsPath, prometheusRegistry)
        registerMiniAppRoutes(miniAppRoot, miniAppIndex)
        registerMiniAppApiRoutes(telegramConfig.botToken, miniCasesConfigService)
        adminTelegramWebhookRoutes(
            adminToken = telegramConfig.adminToken,
            telegramApiClient = telegramApiClient,
            publicBaseUrl = telegramConfig.publicBaseUrl,
            webhookPath = telegramConfig.webhookPath,
            webhookSecretToken = telegramConfig.webhookSecretToken,
            meterRegistry = prometheusRegistry,
        )
    }
}

private fun Application.loadTelegramConfig(): TelegramConfig {
    val botToken =
        configValue(
            propertyKeys = listOf("bot.token", "telegram.bot.token"),
            envKeys = listOf("BOT_TOKEN", "TELEGRAM_BOT_TOKEN"),
            configKeys = listOf("app.telegram.botToken", "telegram.botToken"),
        )?.takeUnless { it.isBlank() }
            ?: error("Telegram bot token is not configured; admin webhook routes require BOT_TOKEN.")

    val adminToken =
        configValue(
            propertyKeys = listOf("admin.token"),
            envKeys = listOf("ADMIN_TOKEN"),
            configKeys = listOf("app.admin.token"),
        )?.takeUnless { it.isBlank() }
            ?: error("ADMIN_TOKEN is not configured.")

    val publicBaseUrl =
        configValue(
            propertyKeys = listOf("telegram.publicBaseUrl"),
            envKeys = listOf("PUBLIC_BASE_URL"),
            configKeys = listOf("app.telegram.publicBaseUrl"),
        )?.takeUnless { it.isBlank() }
            ?: error("PUBLIC_BASE_URL is not configured.")

    val webhookPath =
        configValue(
            propertyKeys = listOf("telegram.webhookPath"),
            envKeys = listOf("WEBHOOK_PATH"),
            configKeys = listOf("app.telegram.webhookPath"),
        )?.takeUnless { it.isBlank() }
            ?: error("WEBHOOK_PATH is not configured.")

    val webhookSecretToken =
        configValue(
            propertyKeys = listOf("telegram.webhookSecretToken"),
            envKeys = listOf("WEBHOOK_SECRET_TOKEN"),
            configKeys = listOf("app.telegram.webhookSecretToken"),
        )?.takeUnless { it.isBlank() }
            ?: error("WEBHOOK_SECRET_TOKEN is not configured.")

    return TelegramConfig(
        botToken = botToken,
        adminToken = adminToken,
        publicBaseUrl = publicBaseUrl,
        webhookPath = webhookPath,
        webhookSecretToken = webhookSecretToken,
    )
}

private data class TelegramConfig(
    val botToken: String,
    val adminToken: String,
    val publicBaseUrl: String,
    val webhookPath: String,
    val webhookSecretToken: String,
)
