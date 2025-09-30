package com.example.giftsbot.antifraud

import com.example.giftsbot.antifraud.RateLimitType.IP
import com.example.giftsbot.antifraud.RateLimitType.SUBJECT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.Serializable

const val RATE_LIMIT_PLUGIN_NAME: String = "RateLimitPlugin"

data class RateLimitConfig(
    val ipEnabled: Boolean,
    val ipCapacity: Int,
    val ipRefillPerSec: Double,
    val ipTtlSeconds: Long,
    val subjectEnabled: Boolean,
    val subjectCapacity: Int,
    val subjectRefillPerSec: Double,
    val subjectTtlSeconds: Long,
    val trustProxy: Boolean,
    val includePaths: List<String>,
    val excludePaths: List<String>,
    val retryAfterFallbackSeconds: Long,
)

class RateLimitPluginConfig {
    lateinit var tokenBucket: TokenBucket
    lateinit var clock: Clock
    lateinit var meterRegistry: MeterRegistry
    lateinit var config: RateLimitConfig
    var subjectResolver: (ApplicationCall) -> Long? = ::extractSubjectId

    internal fun validated(): RateLimitPluginDependencies {
        check(::tokenBucket.isInitialized) { "RateLimitPlugin tokenBucket is not initialized" }
        check(::clock.isInitialized) { "RateLimitPlugin clock is not initialized" }
        check(::meterRegistry.isInitialized) { "RateLimitPlugin meterRegistry is not initialized" }
        check(::config.isInitialized) { "RateLimitPlugin config is not initialized" }
        return RateLimitPluginDependencies(
            tokenBucket = tokenBucket,
            clock = clock,
            meterRegistry = meterRegistry,
            config = config,
            subjectResolver = subjectResolver,
        )
    }
}

internal data class RateLimitPluginDependencies(
    val tokenBucket: TokenBucket,
    val clock: Clock,
    val meterRegistry: MeterRegistry,
    val config: RateLimitConfig,
    val subjectResolver: (ApplicationCall) -> Long?,
)

private enum class RateLimitType(
    val metricTag: String,
) {
    IP("ip"),
    SUBJECT("subject"),
}

private const val ERROR_CODE: String = "rate_limited"

val RateLimitPlugin =
    createApplicationPlugin(
        name = RATE_LIMIT_PLUGIN_NAME,
        createConfiguration = ::RateLimitPluginConfig,
    ) {
        val dependencies = pluginConfig.validated()
        val ipParams =
            BucketParams(
                capacity = dependencies.config.ipCapacity,
                refillTokensPerSecond = dependencies.config.ipRefillPerSec,
                ttlSeconds = dependencies.config.ipTtlSeconds,
            )
        val subjectParams =
            BucketParams(
                capacity = dependencies.config.subjectCapacity,
                refillTokensPerSecond = dependencies.config.subjectRefillPerSec,
                ttlSeconds = dependencies.config.subjectTtlSeconds,
            )
        val metrics = RateLimitMetrics(dependencies.meterRegistry)
        val includePaths = normalizePrefixes(dependencies.config.includePaths)
        val excludePaths = normalizePrefixes(dependencies.config.excludePaths)

        onCall { call ->
            if (!pathMatches(call.request.path(), includePaths, excludePaths)) {
                return@onCall
            }
            metrics.incrementChecked()

            if (dependencies.config.ipEnabled) {
                val ip = extractClientIp(call, dependencies.config.trustProxy)
                val decision =
                    dependencies
                        .tokenBucket
                        .tryConsume(IpKey(ip), ipParams)
                        .withFallback(
                            params = ipParams,
                            fallbackSeconds = dependencies.config.retryAfterFallbackSeconds,
                            nowMillis = dependencies.clock.nowMillis(),
                        )
                if (!decision.allowed) {
                    val retryAfter = decision.retryAfterSeconds ?: dependencies.config.retryAfterFallbackSeconds
                    metrics.recordBlocked(IP, retryAfter)
                    call.respondRateLimited(IP.metricTag, retryAfter)
                    return@onCall
                }
                metrics.recordAllowed(IP)
            }

            if (dependencies.config.subjectEnabled) {
                val subjectId = dependencies.subjectResolver(call)
                if (subjectId != null) {
                    val decision =
                        dependencies
                            .tokenBucket
                            .tryConsume(SubjectKey(subjectId), subjectParams)
                            .withFallback(
                                params = subjectParams,
                                fallbackSeconds = dependencies.config.retryAfterFallbackSeconds,
                                nowMillis = dependencies.clock.nowMillis(),
                            )
                    if (!decision.allowed) {
                        val retryAfter = decision.retryAfterSeconds ?: dependencies.config.retryAfterFallbackSeconds
                        metrics.recordBlocked(SUBJECT, retryAfter)
                        call.respondRateLimited(SUBJECT.metricTag, retryAfter)
                        return@onCall
                    }
                    metrics.recordAllowed(SUBJECT)
                }
            }
        }
    }

private fun normalizePrefixes(prefixes: List<String>): List<String> =
    prefixes.mapNotNull { prefix -> prefix.trim().takeIf { it.isNotEmpty() } }

private fun pathMatches(
    path: String,
    includes: List<String>,
    excludes: List<String>,
): Boolean {
    val included = includes.isEmpty() || includes.any { include -> path.startsWith(include) }
    if (!included) {
        return false
    }
    val excluded = excludes.any { exclude -> path.startsWith(exclude) }
    return !excluded
}

private fun RateLimitDecision.withFallback(
    params: BucketParams,
    fallbackSeconds: Long,
    nowMillis: Long,
): RateLimitDecision {
    if (allowed) {
        return this
    }
    val effectiveRetryAfter = retryAfterSeconds ?: fallbackSeconds
    return if (params.refillTokensPerSecond > 0.0) {
        if (effectiveRetryAfter == retryAfterSeconds) {
            this
        } else {
            copy(retryAfterSeconds = effectiveRetryAfter)
        }
    } else {
        val safeFallback = fallbackSeconds.coerceAtLeast(0L)
        copy(
            retryAfterSeconds = safeFallback,
            resetAtMillis = nowMillis + safeFallback * 1000L,
        )
    }
}

private suspend fun ApplicationCall.respondRateLimited(
    type: String,
    retryAfterSeconds: Long,
) {
    val safeRetryAfter = retryAfterSeconds.coerceAtLeast(0L)
    response.headers.append(HttpHeaders.RetryAfter, safeRetryAfter.toString())
    val payload =
        RateLimitErrorResponse(
            error = ERROR_CODE,
            status = HttpStatusCode.TooManyRequests.value,
            requestId = callId ?: "",
            type = type,
            retryAfterSeconds = safeRetryAfter,
        )
    respond(HttpStatusCode.TooManyRequests, payload)
}

private class RateLimitMetrics(
    registry: MeterRegistry,
) {
    private val blockedIp: Counter = counter(registry, "af_rl_blocked_total", IP.metricTag)
    private val blockedSubject: Counter = counter(registry, "af_rl_blocked_total", SUBJECT.metricTag)
    private val allowedIp: Counter = counter(registry, "af_rl_allowed_total", IP.metricTag)
    private val allowedSubject: Counter = counter(registry, "af_rl_allowed_total", SUBJECT.metricTag)
    private val checked: Counter = Counter.builder("af_rl_checked_total").register(registry)
    private val retryAfterSummary: DistributionSummary =
        DistributionSummary
            .builder("af_rl_retry_after_seconds")
            .register(registry)

    fun incrementChecked() {
        checked.increment()
    }

    fun recordAllowed(type: RateLimitType) {
        when (type) {
            IP -> allowedIp.increment()
            SUBJECT -> allowedSubject.increment()
        }
    }

    fun recordBlocked(
        type: RateLimitType,
        retryAfterSeconds: Long,
    ) {
        val retryValue = retryAfterSeconds.coerceAtLeast(0L).toDouble()
        when (type) {
            IP -> blockedIp.increment()
            SUBJECT -> blockedSubject.increment()
        }
        retryAfterSummary.record(retryValue)
    }

    private fun counter(
        registry: MeterRegistry,
        name: String,
        type: String,
    ): Counter =
        Counter
            .builder(name)
            .tag("type", type)
            .register(registry)
}

@Serializable
private data class RateLimitErrorResponse(
    val error: String,
    val status: Int,
    val requestId: String,
    val type: String,
    val retryAfterSeconds: Long,
)
