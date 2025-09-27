package com.example.app.telegram

import com.example.app.util.configValue
import io.ktor.server.application.Application

internal data class TelegramIntegrationConfig(
    val botToken: String,
    val mode: TelegramMode,
    val webhookPath: String,
    val webhookSecretToken: String,
    val adminToken: String?,
    val publicBaseUrl: String?,
)

internal enum class TelegramMode {
    WEBHOOK,
    LONG_POLLING,
}

internal fun TelegramMode.logValue(): String =
    when (this) {
        TelegramMode.WEBHOOK -> "webhook"
        TelegramMode.LONG_POLLING -> "long_polling"
    }

internal fun Application.loadTelegramIntegrationConfig(): TelegramIntegrationConfig {
    val botToken =
        configValue(
            propertyKeys = listOf("bot.token", "telegram.bot.token"),
            envKeys = listOf("BOT_TOKEN", "TELEGRAM_BOT_TOKEN"),
            configKeys = listOf("app.telegram.botToken", "telegram.botToken"),
        )?.takeUnless { it.isBlank() }
            ?: error("BOT_TOKEN is not configured.")

    val mode =
        parseMode(
            configValue(
                propertyKeys = listOf("bot.mode", "telegram.bot.mode"),
                envKeys = listOf("BOT_MODE", "TELEGRAM_BOT_MODE"),
                configKeys = listOf("app.telegram.mode", "telegram.mode"),
            ),
        )

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

    val adminToken =
        configValue(
            propertyKeys = listOf("admin.token"),
            envKeys = listOf("ADMIN_TOKEN"),
            configKeys = listOf("app.admin.token"),
        )?.takeUnless { it.isBlank() }

    val publicBaseUrl =
        configValue(
            propertyKeys = listOf("telegram.publicBaseUrl"),
            envKeys = listOf("PUBLIC_BASE_URL"),
            configKeys = listOf("app.telegram.publicBaseUrl"),
        )?.takeUnless { it.isBlank() }

    return TelegramIntegrationConfig(
        botToken = botToken,
        mode = mode,
        webhookPath = webhookPath,
        webhookSecretToken = webhookSecretToken,
        adminToken = adminToken,
        publicBaseUrl = publicBaseUrl,
    )
}

private fun parseMode(rawMode: String?): TelegramMode {
    val normalized = rawMode?.trim()?.lowercase()
    return when (normalized) {
        null, "", "webhook" -> TelegramMode.WEBHOOK
        "long_polling", "long-polling" -> TelegramMode.LONG_POLLING
        else -> error("Unsupported BOT_MODE value: $rawMode")
    }
}
