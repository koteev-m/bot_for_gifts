package com.example.app.telegram

import com.example.app.util.configValue
import com.example.giftsbot.telegram.LongPollingRunner
import com.example.giftsbot.telegram.TelegramApiClient
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

private const val TELEGRAM_QUEUE_CAPACITY = 10_000
private const val TELEGRAM_WORKER_COUNT = 1

fun Application.installTelegramIntegration(meterRegistry: MeterRegistry) {
    val config = loadTelegramIntegrationConfig()
    val telegramScope = createTelegramScope()
    val api = TelegramApiClient(botToken = config.botToken)
    val dispatcher = createDispatcher(telegramScope, meterRegistry)

    dispatcher.start(TELEGRAM_WORKER_COUNT)
    log.info("Telegram update dispatcher started with {} worker(s)", TELEGRAM_WORKER_COUNT)

    registerWebhookRoute(
        webhookPath = config.webhookPath,
        expectedSecretToken = config.webhookSecretToken,
        dispatcher = dispatcher,
        meterRegistry = meterRegistry,
    )
    val adminRoutesInstalled =
        registerAdminRoutes(
            config = config,
            api = api,
            meterRegistry = meterRegistry,
        )

    val longPollingRunner =
        startLongPollingIfNeeded(
            mode = config.mode,
            api = api,
            sink = dispatcher,
            scope = telegramScope,
            meterRegistry = meterRegistry,
        )

    val lifecycleInfo = TelegramLifecycleInfo(mode = config.mode, webhookPath = config.webhookPath)

    setupLifecycleHooks(
        info = lifecycleInfo,
        adminRoutesInstalled = adminRoutesInstalled,
        dispatcher = dispatcher,
        scope = telegramScope,
        longPollingRunner = longPollingRunner,
    )
}

private fun Application.loadTelegramIntegrationConfig(): TelegramIntegrationConfig {
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

private fun createDispatcher(
    scope: CoroutineScope,
    meterRegistry: MeterRegistry,
): UpdateDispatcher =
    UpdateDispatcher(
        scope = scope,
        meterRegistry = meterRegistry,
        queueCapacity = TELEGRAM_QUEUE_CAPACITY,
        workers = TELEGRAM_WORKER_COUNT,
    )

private fun Application.registerWebhookRoute(
    webhookPath: String,
    expectedSecretToken: String,
    dispatcher: UpdateDispatcher,
    meterRegistry: MeterRegistry,
) {
    routing {
        telegramWebhookRoutes(
            webhookPath = webhookPath,
            expectedSecretToken = expectedSecretToken,
            sink = dispatcher,
            maxBodyBytes = 1_000_000L,
            meterRegistry = meterRegistry,
        )
    }
    log.info("Telegram webhook route registered at {}", webhookPath)
}

private fun Application.registerAdminRoutes(
    config: TelegramIntegrationConfig,
    api: TelegramApiClient,
    meterRegistry: MeterRegistry,
): Boolean {
    val adminToken = config.adminToken
    val publicBaseUrl = config.publicBaseUrl
    return if (adminToken == null) {
        log.warn("ADMIN_TOKEN is not configured. Telegram admin routes will not be registered.")
        false
    } else if (publicBaseUrl == null) {
        log.warn("PUBLIC_BASE_URL is not configured. Telegram admin routes will not be registered.")
        false
    } else {
        routing {
            adminTelegramWebhookRoutes(
                adminToken = adminToken,
                telegramApiClient = api,
                publicBaseUrl = publicBaseUrl,
                webhookPath = config.webhookPath,
                webhookSecretToken = config.webhookSecretToken,
                meterRegistry = meterRegistry,
            )
        }
        log.info("Telegram admin webhook routes registered")
        true
    }
}

private fun Application.startLongPollingIfNeeded(
    mode: TelegramMode,
    api: TelegramApiClient,
    sink: UpdateSink,
    scope: CoroutineScope,
    meterRegistry: MeterRegistry,
): LongPollingRunner? {
    if (mode != TelegramMode.LONG_POLLING) {
        return null
    }
    val runner =
        LongPollingRunner(
            api = api,
            sink = sink,
            scope = scope,
            meterRegistry = meterRegistry,
        )
    return runCatching {
        runner.start()
        log.info("Telegram long polling runner started")
        runner
    }.getOrElse { cause ->
        if (cause !is CancellationException) {
            log.error("Failed to start Telegram long polling runner", cause)
        }
        throw cause
    }
}

private fun Application.setupLifecycleHooks(
    info: TelegramLifecycleInfo,
    adminRoutesInstalled: Boolean,
    dispatcher: UpdateDispatcher,
    scope: CoroutineScope,
    longPollingRunner: LongPollingRunner?,
) {
    monitor.subscribe(ApplicationStarted) {
        log.info("Telegram integration started: mode={} webhookPath={}", info.mode.logValue(), info.webhookPath)
        if (adminRoutesInstalled) {
            log.info("Telegram admin routes enabled")
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Telegram integration stopping")
        try {
            runBlocking {
                longPollingRunner?.let { runner ->
                    runCatching { runner.stop() }
                        .onFailure { log.error("Failed to stop Telegram long polling runner", it) }
                }
                runCatching { dispatcher.close() }
                    .onFailure { log.error("Failed to close Telegram update dispatcher", it) }
            }
        } finally {
            scope.cancel()
        }
    }
}

private fun TelegramMode.logValue(): String =
    when (this) {
        TelegramMode.WEBHOOK -> "webhook"
        TelegramMode.LONG_POLLING -> "long_polling"
    }

private fun createTelegramScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("telegram"))

private data class TelegramIntegrationConfig(
    val botToken: String,
    val mode: TelegramMode,
    val webhookPath: String,
    val webhookSecretToken: String,
    val adminToken: String?,
    val publicBaseUrl: String?,
)

private data class TelegramLifecycleInfo(
    val mode: TelegramMode,
    val webhookPath: String,
)

private enum class TelegramMode {
    WEBHOOK,
    LONG_POLLING,
}
