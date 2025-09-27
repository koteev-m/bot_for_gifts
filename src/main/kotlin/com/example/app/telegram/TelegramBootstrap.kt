package com.example.app.telegram

import com.example.app.payments.RefundService
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.telegram.LongPollingRunner
import com.example.giftsbot.telegram.TelegramApiClient
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

private val TELEGRAM_DISPATCHER_SETTINGS =
    UpdateDispatcherSettings(queueCapacity = 10_000, workers = 1)

fun Application.installTelegramIntegration(meterRegistry: MeterRegistry) {
    val config = loadTelegramIntegrationConfig()
    val telegramScope = createTelegramScope()
    val api = TelegramApiClient(botToken = config.botToken)
    val casesRepository = CasesRepository(meterRegistry = meterRegistry).also { it.reload() }
    val preCheckoutHandler = createPreCheckoutHandler(api, casesRepository, meterRegistry)
    val refundService = RefundService(api, meterRegistry)
    val successfulPaymentHandler =
        createSuccessfulPaymentHandler(
            api = api,
            casesRepository = casesRepository,
            refundService = refundService,
            meterRegistry = meterRegistry,
        )
    val router = WebhookUpdateRouter(preCheckoutHandler, successfulPaymentHandler)
    val dispatcher = createDispatcher(telegramScope, meterRegistry, router, TELEGRAM_DISPATCHER_SETTINGS)

    dispatcher.start()
    log.info(
        "Telegram update dispatcher started with {} worker(s)",
        TELEGRAM_DISPATCHER_SETTINGS.workers,
    )

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

private data class TelegramLifecycleInfo(
    val mode: TelegramMode,
    val webhookPath: String,
)
