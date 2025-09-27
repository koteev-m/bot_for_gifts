package com.example.app.telegram

import com.example.app.payments.AwardService
import com.example.app.payments.GiftCatalogCache
import com.example.app.payments.PaymentSupport
import com.example.app.payments.PreCheckoutHandler
import com.example.app.payments.RefundService
import com.example.app.payments.SuccessfulPaymentHandler
import com.example.app.payments.TelegramAwardService
import com.example.app.payments.loadPaymentsConfig
import com.example.app.rng.getRngService
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.telegram.TelegramApiClient
import io.ktor.server.application.Application
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal fun createTelegramScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("telegram"))

internal fun createAwardService(
    api: TelegramApiClient,
    casesRepository: CasesRepository,
    refundService: RefundService,
    meterRegistry: MeterRegistry,
): AwardService {
    val giftCatalogCache = GiftCatalogCache(api)
    return TelegramAwardService(
        telegramApiClient = api,
        casesRepository = casesRepository,
        giftCatalogCache = giftCatalogCache,
        refundService = refundService,
        meterRegistry = meterRegistry,
    )
}

internal fun Application.createSuccessfulPaymentHandler(
    api: TelegramApiClient,
    casesRepository: CasesRepository,
    refundService: RefundService,
    meterRegistry: MeterRegistry,
): SuccessfulPaymentHandler {
    val paymentsConfig = loadPaymentsConfig()
    return SuccessfulPaymentHandler(
        telegramApiClient = api,
        rngService = getRngService(),
        casesRepository = casesRepository,
        awardService = createAwardService(api, casesRepository, refundService, meterRegistry),
        paymentSupport = PaymentSupport(config = paymentsConfig, refundService = refundService),
        meterRegistry = meterRegistry,
    )
}

internal fun createPreCheckoutHandler(
    api: TelegramApiClient,
    casesRepository: CasesRepository,
    meterRegistry: MeterRegistry,
): PreCheckoutHandler = PreCheckoutHandler(api, casesRepository, meterRegistry)

internal fun createDispatcher(
    scope: CoroutineScope,
    meterRegistry: MeterRegistry,
    router: WebhookUpdateRouter,
    settings: UpdateDispatcherSettings,
): UpdateDispatcher =
    UpdateDispatcher(
        scope = scope,
        meterRegistry = meterRegistry,
        settings = settings,
        handleUpdate = router::route,
    )
