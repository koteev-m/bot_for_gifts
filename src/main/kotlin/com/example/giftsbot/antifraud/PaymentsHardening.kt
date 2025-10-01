package com.example.giftsbot.antifraud

import com.example.giftsbot.antifraud.velocity.AfEvent
import com.example.giftsbot.antifraud.velocity.AfEventType
import com.example.giftsbot.antifraud.velocity.VelocityAction
import com.example.giftsbot.antifraud.velocity.VelocityChecker
import com.example.giftsbot.antifraud.velocity.VelocityDecision
import com.example.giftsbot.antifraud.velocity.VelocityFlag
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

val PAYMENTS_AF_CONTEXT_KEY = io.ktor.util.AttributeKey<PaymentsAntifraudContext>("antifraud.paymentsContext")

data class AfOutcome(
    val decision: VelocityDecision,
    val banned: Boolean,
    val banReason: String? = null,
)

data class PaymentsAntifraudContext(
    val velocityEnabled: Boolean,
    val trustProxy: Boolean,
    val autobanEnabled: Boolean,
    val autobanScore: Int,
    val autobanTtlSeconds: Long,
    val retryAfterSeconds: Long,
    val velocityChecker: VelocityChecker?,
    val suspiciousIpStore: SuspiciousIpStore,
    val meterRegistry: MeterRegistry,
)

data class StoredUpdateContext(
    val call: ApplicationCall,
    val ip: String,
    val subjectId: Long?,
    val userAgent: String?,
    val createdAtMs: Long,
)

@Suppress("TooManyFunctions")
object PaymentsHardening {
    private const val UPDATE_CONTEXT_TTL_MILLIS = 300_000L
    private val logger = LoggerFactory.getLogger(PaymentsHardening::class.java)

    @Volatile
    private var cachedContext: PaymentsAntifraudContext? = null
    private val updateContexts = ConcurrentHashMap<Long, StoredUpdateContext>()

    fun configure(context: PaymentsAntifraudContext?) {
        cachedContext = context
    }

    fun context(): PaymentsAntifraudContext? = cachedContext

    fun context(application: Application): PaymentsAntifraudContext? {
        val attributes = application.attributes
        return if (attributes.contains(PAYMENTS_AF_CONTEXT_KEY)) {
            attributes[PAYMENTS_AF_CONTEXT_KEY]
        } else {
            cachedContext
        }
    }

    @Suppress("LongParameterList")
    fun rememberUpdateContext(
        updateId: Long,
        call: ApplicationCall,
        ip: String,
        subjectId: Long?,
        userAgent: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        updateContexts[updateId] =
            StoredUpdateContext(
                call = call,
                ip = ip,
                subjectId = subjectId,
                userAgent = userAgent,
                createdAtMs = nowMs,
            )
        cleanupExpired(nowMs)
    }

    fun consumeUpdateContext(
        updateId: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): StoredUpdateContext? {
        val context = updateContexts.remove(updateId)
        if (context == null) {
            return null
        }
        return if (nowMs - context.createdAtMs > UPDATE_CONTEXT_TTL_MILLIS) {
            null
        } else {
            context
        }
    }

    @Suppress("LongParameterList")
    suspend fun checkAndMaybeAutoban(
        call: ApplicationCall,
        eventType: AfEventType,
        ip: String,
        subjectId: Long?,
        path: String,
        ua: String?,
        velocity: VelocityChecker,
        suspiciousStore: SuspiciousIpStore,
        meterRegistry: MeterRegistry,
        autobanEnabled: Boolean,
        autobanScore: Int,
        autobanTtlSeconds: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): AfOutcome {
        val event =
            AfEvent(
                type = eventType,
                ip = ip,
                subjectId = subjectId,
                path = path,
                userAgent = ua,
                timestampMs = nowMs,
            )
        val decision = velocity.checkAndRecord(event)
        recordFlags(decision.flags, meterRegistry)
        recordDecision(eventType, decision.action, meterRegistry)
        val (banned, banReason) =
            if (autobanEnabled) {
                applyAutobanIfNeeded(
                    eventType = eventType,
                    decision = decision,
                    ip = ip,
                    suspiciousStore = suspiciousStore,
                    autobanScore = autobanScore,
                    autobanTtlSeconds = autobanTtlSeconds,
                    nowMs = nowMs,
                )
            } else {
                false to null
            }
        logDecision(call, eventType, decision, banned, banReason)
        return AfOutcome(decision = decision, banned = banned, banReason = banReason)
    }

    suspend fun respondTooManyRequests(
        call: ApplicationCall,
        retryAfterSeconds: Long,
        requestId: String?,
        type: String,
    ) {
        val safeRetryAfter = retryAfterSeconds.coerceAtLeast(0L)
        val payload =
            mapOf(
                "error" to "rate_limited",
                "status" to HttpStatusCode.TooManyRequests.value,
                "requestId" to requestId,
                "type" to type,
                "retryAfterSeconds" to safeRetryAfter,
            )
        call.respond(HttpStatusCode.TooManyRequests, payload)
    }

    suspend fun answerPreCheckoutLimited(
        api: com.example.giftsbot.telegram.TelegramApiClient,
        preCheckoutQueryId: String,
        errorMessage: String = "Too many requests. Try again later.",
    ) {
        api.answerPreCheckoutQuery(
            queryId = preCheckoutQueryId,
            ok = false,
            errorMessage = errorMessage,
        )
    }

    private fun recordFlags(
        flags: Set<VelocityFlag>,
        meterRegistry: MeterRegistry,
    ) {
        for (flag in flags) {
            meterRegistry.counter("pay_af_flags_total", "flag", flag.name).increment()
        }
    }

    private fun recordDecision(
        eventType: AfEventType,
        action: VelocityAction,
        meterRegistry: MeterRegistry,
    ) {
        val typeTag =
            when (eventType) {
                AfEventType.INVOICE -> "invoice"
                AfEventType.PRE_CHECKOUT -> "precheckout"
                AfEventType.SUCCESS -> "success"
                AfEventType.WEBHOOK -> "webhook"
                else -> "other"
            }
        meterRegistry.counter("pay_af_decisions_total", "type", typeTag, "action", action.name).increment()
    }

    @Suppress("LongParameterList")
    private fun applyAutobanIfNeeded(
        eventType: AfEventType,
        decision: VelocityDecision,
        ip: String,
        suspiciousStore: SuspiciousIpStore,
        autobanScore: Int,
        autobanTtlSeconds: Long,
        nowMs: Long,
    ): Pair<Boolean, String?> {
        if (decision.score < autobanScore) {
            return false to null
        }
        val reason =
            when (eventType) {
                AfEventType.SUCCESS -> "success_high_score=${decision.score}"
                else -> "velocity_score=${decision.score}"
            }
        return when (eventType) {
            AfEventType.INVOICE, AfEventType.PRE_CHECKOUT -> {
                suspiciousStore.ban(ip, autobanTtlSeconds, reason, nowMs)
                true to reason
            }
            AfEventType.SUCCESS -> {
                suspiciousStore.markSuspicious(ip, reason, nowMs)
                false to reason
            }
            else -> false to null
        }
    }

    private fun logDecision(
        call: ApplicationCall,
        eventType: AfEventType,
        decision: VelocityDecision,
        banned: Boolean,
        banReason: String?,
    ) {
        val callId = call.callId ?: "-"
        if (decision.action == VelocityAction.LOG_ONLY && !banned) {
            logger.debug(
                "payments velocity check: requestId={} type={} action={} score={} flags={}",
                callId,
                eventType,
                decision.action,
                decision.score,
                decision.flags.joinToString(separator = ","),
            )
            return
        }
        logger.warn(
            "payments velocity decision: requestId={} type={} action={} score={} flags={} banned={} reason={}",
            callId,
            eventType,
            decision.action,
            decision.score,
            decision.flags.joinToString(separator = ","),
            banned,
            banReason,
        )
    }

    private fun cleanupExpired(nowMs: Long) {
        val threshold = nowMs - UPDATE_CONTEXT_TTL_MILLIS
        updateContexts.entries.removeIf { (_, stored) -> stored.createdAtMs < threshold }
    }
}
