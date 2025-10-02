package com.example.giftsbot.testutil

import com.example.giftsbot.antifraud.InMemorySuspiciousIpStore
import com.example.giftsbot.antifraud.PAYMENTS_AF_CONTEXT_KEY
import com.example.giftsbot.antifraud.PaymentsHardening
import com.example.giftsbot.antifraud.SuspiciousIpStore
import com.example.giftsbot.antifraud.TokenBucket
import com.example.giftsbot.antifraud.extractClientIp
import com.example.giftsbot.antifraud.extractSubjectId
import com.example.giftsbot.antifraud.installAntifraudIntegration
import com.example.giftsbot.antifraud.store.InMemoryBucketStore
import com.example.giftsbot.antifraud.velocity.AfEventType
import com.example.giftsbot.antifraud.velocity.VelocityAction
import com.example.giftsbot.antifraud.velocity.VelocityChecker
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val WEBHOOK_PATH = "/telegram/webhook"
private const val WEBHOOK_SECRET = "test-secret"

@Suppress("LongParameterList")
fun Application.withAntifraudTestSetup(
    adminToken: String = "admin",
    trustProxy: Boolean = true,
    includePaths: List<String> = listOf(WEBHOOK_PATH, "/api/miniapp/invoice"),
    excludePaths: List<String> = emptyList(),
    rlIpBurst: Int = 1,
    rlIpRps: Double = 0.0,
    rlSubjBurst: Int = 1,
    rlSubjRps: Double = 0.0,
    velocityChecker: VelocityChecker? = null,
    suspiciousIpStore: SuspiciousIpStore? = null,
    installPaymentsAndWebhook: Boolean = true,
    telegramClient: FakeTelegramApiClient = FakeTelegramApiClient(),
): AfTestComponents {
    val mapConfig = requireMapConfig()
    configureAntifraudConfig(
        config = mapConfig,
        adminToken = adminToken,
        trustProxy = trustProxy,
        includePaths = includePaths,
        excludePaths = excludePaths,
        rlIpBurst = rlIpBurst,
        rlIpRps = rlIpRps,
        rlSubjBurst = rlSubjBurst,
        rlSubjRps = rlSubjRps,
    )

    val core = createCoreDependencies(velocityChecker, suspiciousIpStore)
    installJsonSupport()
    installTestAntifraud(core)

    val services = registerTestServices(core, installPaymentsAndWebhook, telegramClient)
    return createTestComponents(core, services, telegramClient)
}

private fun Application.requireMapConfig(): MapApplicationConfig =
    requireNotNull(environment.config as? MapApplicationConfig) {
        "withAntifraudTestSetup requires MapApplicationConfig"
    }

private fun Application.installJsonSupport() {
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}

@Suppress("LongParameterList")
private fun configureAntifraudConfig(
    config: MapApplicationConfig,
    adminToken: String,
    trustProxy: Boolean,
    includePaths: List<String>,
    excludePaths: List<String>,
    rlIpBurst: Int,
    rlIpRps: Double,
    rlSubjBurst: Int,
    rlSubjRps: Double,
) {
    config.put("app.antifraud.admin.token", adminToken)
    config.put("app.antifraud.ban.defaultTtlSeconds", "60")
    config.put("app.antifraud.rl.trustProxy", trustProxy.toString())
    config.put("app.antifraud.rl.includePaths", includePaths.joinToString(","))
    config.put("app.antifraud.rl.excludePaths", excludePaths.joinToString(","))
    config.put("app.antifraud.rl.retryAfter", "60")
    config.put("app.antifraud.rl.ip.enabled", "true")
    config.put("app.antifraud.rl.ip.capacity", rlIpBurst.toString())
    config.put("app.antifraud.rl.ip.rps", rlIpRps.toString())
    config.put("app.antifraud.rl.ip.ttlSeconds", "60")
    config.put("app.antifraud.rl.subject.enabled", "true")
    config.put("app.antifraud.rl.subject.capacity", rlSubjBurst.toString())
    config.put("app.antifraud.rl.subject.rps", rlSubjRps.toString())
    config.put("app.antifraud.rl.subject.ttlSeconds", "60")
    config.put("app.antifraud.velocity.enabled", "true")
    config.put("app.antifraud.velocity.autoban.enabled", "false")
    config.put("app.antifraud.velocity.autoban.score", "90")
    config.put("app.antifraud.velocity.autoban.ttlSeconds", "3600")
}

private fun createCoreDependencies(
    velocityChecker: VelocityChecker?,
    suspiciousIpStore: SuspiciousIpStore?,
): AfCore {
    val clock = TestClock()
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val bucketStore = InMemoryBucketStore()
    val tokenBucket = TokenBucket(bucketStore, clock)
    val store = suspiciousIpStore ?: ControlledSuspiciousIpStore(clock)
    val velocity = velocityChecker ?: VelocityChecker(clock = clock)
    return AfCore(
        clock = clock,
        meterRegistry = meterRegistry,
        bucketStore = bucketStore,
        tokenBucket = tokenBucket,
        suspiciousStore = store,
        velocityChecker = velocity,
    )
}

private fun Application.registerTestServices(
    core: AfCore,
    installPaymentsAndWebhook: Boolean,
    telegramClient: FakeTelegramApiClient,
): AfServices {
    val invoiceService = RecordingInvoiceService()
    val paymentProcessor = RecordingSuccessfulPaymentProcessor()
    configureAfRoutes(
        installPaymentsAndWebhook = installPaymentsAndWebhook,
        meterRegistry = core.meterRegistry,
        invoiceService = invoiceService,
        paymentProcessor = paymentProcessor,
        telegramClient = telegramClient,
    )
    return AfServices(invoiceService = invoiceService, paymentProcessor = paymentProcessor)
}

private fun createTestComponents(
    core: AfCore,
    services: AfServices,
    telegramClient: FakeTelegramApiClient,
): AfTestComponents =
    AfTestComponents(
        meterRegistry = core.meterRegistry,
        clock = core.clock,
        invoiceService = services.invoiceService,
        paymentProcessor = services.paymentProcessor,
        telegramClient = telegramClient,
        suspiciousIpStore = core.suspiciousStore,
        velocityChecker = core.velocityChecker,
        webhookSecret = WEBHOOK_SECRET,
        webhookPath = WEBHOOK_PATH,
        tokenBucket = core.tokenBucket,
    )

private fun Application.installTestAntifraud(core: AfCore) {
    installAntifraudIntegration(
        meterRegistry = core.meterRegistry,
        store = core.bucketStore,
        clock = core.clock,
        subjectResolver = ::extractSubjectId,
        suspiciousIpStore = core.suspiciousStore,
    )
    overridePaymentsContext(core.velocityChecker, core.suspiciousStore, core.meterRegistry)
}

private fun Application.overridePaymentsContext(
    velocityChecker: VelocityChecker,
    suspiciousStore: SuspiciousIpStore,
    meterRegistry: PrometheusMeterRegistry,
) {
    if (!attributes.contains(PAYMENTS_AF_CONTEXT_KEY)) {
        return
    }
    val base = attributes[PAYMENTS_AF_CONTEXT_KEY]
    val updated =
        base.copy(
            velocityEnabled = true,
            velocityChecker = velocityChecker,
            suspiciousIpStore = suspiciousStore,
            meterRegistry = meterRegistry,
        )
    attributes.put(PAYMENTS_AF_CONTEXT_KEY, updated)
    PaymentsHardening.configure(updated)
}

private fun Application.configureAfRoutes(
    installPaymentsAndWebhook: Boolean,
    meterRegistry: PrometheusMeterRegistry,
    invoiceService: RecordingInvoiceService,
    paymentProcessor: RecordingSuccessfulPaymentProcessor,
    telegramClient: FakeTelegramApiClient,
) {
    routing {
        get("/ping") { call.respondText("pong") }
        get("/ping/excluded") { call.respondText("pong") }
        get("/metrics") { call.respondText(meterRegistry.scrape(), ContentType.Text.Plain) }
        if (installPaymentsAndWebhook) {
            registerInvoiceRoute(invoiceService)
            registerWebhookRoute(paymentProcessor, telegramClient)
        }
    }
}

private fun Route.registerInvoiceRoute(invoiceService: RecordingInvoiceService) {
    post("/api/miniapp/invoice") {
        val request = call.receive<MiniAppInvoiceRequest>()
        val context = PaymentsHardening.context(call.application)
        if (context != null && context.velocityEnabled && context.velocityChecker != null) {
            val subjectId = extractSubjectId(call) ?: request.userId
            val outcome =
                PaymentsHardening.checkAndMaybeAutoban(
                    call = call,
                    eventType = AfEventType.INVOICE,
                    ip = extractClientIp(call, context.trustProxy),
                    subjectId = subjectId,
                    path = "/api/miniapp/invoice",
                    ua = call.request.header(HttpHeaders.UserAgent),
                    velocity = context.velocityChecker!!,
                    suspiciousStore = context.suspiciousIpStore,
                    meterRegistry = context.meterRegistry,
                    autobanEnabled = context.autobanEnabled,
                    autobanScore = context.autobanScore,
                    autobanTtlSeconds = context.autobanTtlSeconds,
                )
            if (outcome.decision.action == VelocityAction.HARD_BLOCK_BEFORE_PAYMENT) {
                context.meterRegistry.counter("pay_af_blocks_total", "type", "invoice").increment()
                respondVelocityLimit(call, context.retryAfterSeconds)
                return@post
            }
        }
        val response = invoiceService.createInvoice(request.caseId, request.userId)
        call.respond(HttpStatusCode.OK, response)
    }
}

private fun Route.registerWebhookRoute(
    paymentProcessor: RecordingSuccessfulPaymentProcessor,
    telegramClient: FakeTelegramApiClient,
) {
    post(WEBHOOK_PATH) {
        if (call.request.header("X-Telegram-Bot-Api-Secret-Token") != WEBHOOK_SECRET) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
            return@post
        }
        val update = call.receive<com.example.giftsbot.telegram.UpdateDto>()
        val context = PaymentsHardening.context(call.application)
        if (context != null) {
            applyWebhookAntifraud(call, update, context)
            processPreCheckoutIfNeeded(call, update, context, telegramClient)
            processSuccessfulPaymentIfNeeded(call, update, context, paymentProcessor)
        }
        call.respondText("ok")
    }
}

private suspend fun applyWebhookAntifraud(
    call: io.ktor.server.application.ApplicationCall,
    update: com.example.giftsbot.telegram.UpdateDto,
    context: com.example.giftsbot.antifraud.PaymentsAntifraudContext,
) {
    val velocity = context.velocityChecker
    if (velocity == null || !context.velocityEnabled) {
        return
    }
    val ip = extractClientIp(call, context.trustProxy)
    val subjectId = update.message?.from?.id ?: update.pre_checkout_query?.from?.id
    PaymentsHardening.checkAndMaybeAutoban(
        call = call,
        eventType = AfEventType.WEBHOOK,
        ip = ip,
        subjectId = subjectId,
        path = WEBHOOK_PATH,
        ua = call.request.header(HttpHeaders.UserAgent),
        velocity = velocity,
        suspiciousStore = context.suspiciousIpStore,
        meterRegistry = context.meterRegistry,
        autobanEnabled = context.autobanEnabled,
        autobanScore = context.autobanScore,
        autobanTtlSeconds = context.autobanTtlSeconds,
    )
}

@Suppress("NestedBlockDepth", "ReturnCount")
private suspend fun respondVelocityLimit(
    call: io.ktor.server.application.ApplicationCall,
    retryAfterSeconds: Long?,
) {
    val payload =
        RateLimitPayload(
            error = "rate_limited",
            status = HttpStatusCode.TooManyRequests.value,
            requestId = call.callId,
            type = "velocity",
            retryAfterSeconds = retryAfterSeconds,
        )
    if (retryAfterSeconds != null) {
        call.response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    }
    call.respond(HttpStatusCode.TooManyRequests, payload)
}

@Suppress("NestedBlockDepth", "ReturnCount")
private suspend fun processPreCheckoutIfNeeded(
    call: io.ktor.server.application.ApplicationCall,
    update: com.example.giftsbot.telegram.UpdateDto,
    context: com.example.giftsbot.antifraud.PaymentsAntifraudContext,
    telegramClient: FakeTelegramApiClient,
) {
    val query = update.pre_checkout_query ?: return
    if (context.velocityEnabled) {
        val velocity = context.velocityChecker
        if (velocity != null) {
            PaymentsHardening.rememberUpdateContext(
                updateId = update.update_id,
                call = call,
                ip = extractClientIp(call, context.trustProxy),
                subjectId = query.from.id,
                userAgent = call.request.header(HttpHeaders.UserAgent),
            )
            val stored = PaymentsHardening.consumeUpdateContext(update.update_id)
            if (stored != null) {
                val outcome =
                    PaymentsHardening.checkAndMaybeAutoban(
                        call = stored.call,
                        eventType = AfEventType.PRE_CHECKOUT,
                        ip = stored.ip,
                        subjectId = stored.subjectId ?: query.from.id,
                        path = "/telegram/pre-checkout",
                        ua = stored.userAgent,
                        velocity = velocity,
                        suspiciousStore = context.suspiciousIpStore,
                        meterRegistry = context.meterRegistry,
                        autobanEnabled = context.autobanEnabled,
                        autobanScore = context.autobanScore,
                        autobanTtlSeconds = context.autobanTtlSeconds,
                    )
                if (outcome.decision.action == VelocityAction.HARD_BLOCK_BEFORE_PAYMENT) {
                    context.meterRegistry.counter("pay_af_blocks_total", "type", "precheckout").increment()
                    PaymentsHardening.answerPreCheckoutLimited(telegramClient.client, query.id)
                }
            }
        }
    }
}

@Suppress("ReturnCount")
private suspend fun processSuccessfulPaymentIfNeeded(
    call: io.ktor.server.application.ApplicationCall,
    update: com.example.giftsbot.telegram.UpdateDto,
    context: com.example.giftsbot.antifraud.PaymentsAntifraudContext,
    paymentProcessor: RecordingSuccessfulPaymentProcessor,
) {
    val message = update.message ?: return
    val payment = message.successful_payment ?: return
    PaymentsHardening.rememberUpdateContext(
        updateId = update.update_id,
        call = call,
        ip = extractClientIp(call, context.trustProxy),
        subjectId = message.from?.id,
        userAgent = call.request.header(HttpHeaders.UserAgent),
    )
    val stored = PaymentsHardening.consumeUpdateContext(update.update_id)
    val velocity = context.velocityChecker
    if (stored != null && context.velocityEnabled && velocity != null) {
        PaymentsHardening.checkAndMaybeAutoban(
            call = stored.call,
            eventType = AfEventType.SUCCESS,
            ip = stored.ip,
            subjectId = stored.subjectId ?: message.from?.id,
            path = "/telegram/success",
            ua = stored.userAgent,
            velocity = velocity,
            suspiciousStore = context.suspiciousIpStore,
            meterRegistry = context.meterRegistry,
            autobanEnabled = context.autobanEnabled,
            autobanScore = context.autobanScore,
            autobanTtlSeconds = context.autobanTtlSeconds,
        )
    }
    paymentProcessor.record(payment.telegram_payment_charge_id)
}

data class AfTestComponents(
    val meterRegistry: PrometheusMeterRegistry,
    val clock: TestClock,
    val invoiceService: RecordingInvoiceService,
    val paymentProcessor: RecordingSuccessfulPaymentProcessor,
    val telegramClient: FakeTelegramApiClient,
    val suspiciousIpStore: SuspiciousIpStore,
    val velocityChecker: VelocityChecker,
    val webhookSecret: String,
    val webhookPath: String,
    val tokenBucket: TokenBucket,
)

private data class AfServices(
    val invoiceService: RecordingInvoiceService,
    val paymentProcessor: RecordingSuccessfulPaymentProcessor,
)

class RecordingInvoiceService {
    private val calls = mutableListOf<String>()

    fun callCount(): Int = calls.size

    fun lastCaseId(): String? = calls.lastOrNull()

    fun createInvoice(
        caseId: String,
        userId: Long,
    ): MiniAppInvoiceResponse {
        calls += caseId
        return MiniAppInvoiceResponse(invoiceLink = "https://example.test/$caseId/$userId")
    }
}

class RecordingSuccessfulPaymentProcessor {
    private val processed = LinkedHashSet<String>()
    private var duplicates = 0

    fun record(chargeId: String) {
        if (!processed.add(chargeId)) {
            duplicates += 1
        }
    }

    fun processedChargeIds(): List<String> = processed.toList()

    fun duplicateCount(): Int = duplicates
}

@Serializable
data class MiniAppInvoiceRequest(
    val caseId: String,
    val userId: Long = 0,
)

@Serializable
data class MiniAppInvoiceResponse(
    val invoiceLink: String,
)

@Serializable
data class RateLimitPayload(
    val error: String,
    val status: Int,
    val requestId: String?,
    val type: String,
    val retryAfterSeconds: Long?,
)

private class ControlledSuspiciousIpStore(
    private val clock: TestClock,
) : SuspiciousIpStore {
    private val delegate = InMemorySuspiciousIpStore()

    override fun markSuspicious(
        ip: String,
        reason: String?,
        nowMs: Long,
    ) = delegate.markSuspicious(ip, reason, clock.nowMillis())

    override fun ban(
        ip: String,
        ttlSeconds: Long?,
        reason: String?,
        nowMs: Long,
    ) = delegate.ban(ip, ttlSeconds, reason, clock.nowMillis())

    override fun unban(
        ip: String,
        nowMs: Long,
    ) = delegate.unban(ip, clock.nowMillis())

    override fun isBanned(
        ip: String,
        nowMs: Long,
    ) = delegate.isBanned(ip, clock.nowMillis())

    override fun listRecent(
        limit: Int,
        sinceMs: Long?,
        nowMs: Long,
    ) = delegate.listRecent(limit, sinceMs, clock.nowMillis())

    override fun listBanned(
        limit: Int,
        nowMs: Long,
    ) = delegate.listBanned(limit, clock.nowMillis())
}

private data class AfCore(
    val clock: TestClock,
    val meterRegistry: PrometheusMeterRegistry,
    val bucketStore: InMemoryBucketStore,
    val tokenBucket: TokenBucket,
    val suspiciousStore: SuspiciousIpStore,
    val velocityChecker: VelocityChecker,
)
