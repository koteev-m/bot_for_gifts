package com.example.giftsbot.antifraud

import com.example.giftsbot.antifraud.store.InMemoryBucketStore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry

private const val CONFIG_PREFIX = "app.antifraud"
private const val CONFIG_IP_ENABLED = "$CONFIG_PREFIX.rl.ip.enabled"
private const val CONFIG_IP_RPS = "$CONFIG_PREFIX.rl.ip.rps"
private const val CONFIG_IP_BURST = "$CONFIG_PREFIX.rl.ip.capacity"
private const val CONFIG_IP_TTL = "$CONFIG_PREFIX.rl.ip.ttlSeconds"
private const val CONFIG_SUBJECT_ENABLED = "$CONFIG_PREFIX.rl.subject.enabled"
private const val CONFIG_SUBJECT_RPS = "$CONFIG_PREFIX.rl.subject.rps"
private const val CONFIG_SUBJECT_BURST = "$CONFIG_PREFIX.rl.subject.capacity"
private const val CONFIG_SUBJECT_TTL = "$CONFIG_PREFIX.rl.subject.ttlSeconds"
private const val CONFIG_TRUST_PROXY = "$CONFIG_PREFIX.rl.trustProxy"
private const val CONFIG_INCLUDE_PATHS = "$CONFIG_PREFIX.rl.includePaths"
private const val CONFIG_EXCLUDE_PATHS = "$CONFIG_PREFIX.rl.excludePaths"
private const val CONFIG_RETRY_AFTER = "$CONFIG_PREFIX.rl.retryAfter"
private const val CONFIG_ADMIN_TOKEN = "$CONFIG_PREFIX.admin.token"
private const val CONFIG_BAN_DEFAULT_TTL = "$CONFIG_PREFIX.ban.defaultTtlSeconds"

private const val ENV_IP_ENABLED = "RL_IP_ENABLED"
private const val ENV_IP_RPS = "RL_IP_RPS"
private const val ENV_IP_BURST = "RL_IP_BURST"
private const val ENV_IP_TTL = "RL_IP_TTL_SECONDS"
private const val ENV_SUBJECT_ENABLED = "RL_SUBJECT_ENABLED"
private const val ENV_SUBJECT_RPS = "RL_SUBJECT_RPS"
private const val ENV_SUBJECT_BURST = "RL_SUBJECT_BURST"
private const val ENV_SUBJECT_TTL = "RL_SUBJECT_TTL_SECONDS"
private const val ENV_TRUST_PROXY = "RL_TRUST_PROXY"
private const val ENV_INCLUDE_PATHS = "RL_INCLUDE_PATHS"
private const val ENV_EXCLUDE_PATHS = "RL_EXCLUDE_PATHS"
private const val ENV_RETRY_AFTER = "RL_RETRY_AFTER_SECONDS"
private const val ENV_ADMIN_TOKEN = "ADMIN_TOKEN"
private const val ENV_BAN_DEFAULT_TTL = "RL_BAN_DEFAULT_TTL_SECONDS"

val SUSPICIOUS_IP_STORE_KEY: AttributeKey<SuspiciousIpStore> = AttributeKey("antifraud.suspiciousIpStore")

fun Application.installAntifraudIntegration(
    meterRegistry: MeterRegistry,
    store: BucketStore? = null,
    clock: Clock = SystemClock,
    subjectResolver: ((ApplicationCall) -> Long?)? = null,
    suspiciousIpStore: SuspiciousIpStore = InMemorySuspiciousIpStore(),
) {
    val rateLimitConfig = loadConfig()
    val bucketStore = store ?: InMemoryBucketStore()
    val sharedTokenBucket = TokenBucket(bucketStore, clock)
    if (!attributes.contains(SUSPICIOUS_IP_STORE_KEY)) {
        attributes.put(SUSPICIOUS_IP_STORE_KEY, suspiciousIpStore)
    }
    val adminToken = readAdminToken()
    val defaultBanTtl = readBanDefaultTtl()
    install(RateLimitPlugin) {
        tokenBucket = sharedTokenBucket
        this.clock = clock
        this.meterRegistry = meterRegistry
        config = rateLimitConfig
        subjectResolver?.let { resolver -> this.subjectResolver = resolver }
        this.suspiciousIpStore = suspiciousIpStore
    }
    if (adminToken == null) {
        environment.log.warn("ADMIN_TOKEN is not configured. Antifraud admin routes will not be registered.")
    } else {
        routing {
            adminAntifraudRoutes(
                adminToken = adminToken,
                store = suspiciousIpStore,
                meterRegistry = meterRegistry,
                defaultBanTtlSeconds = defaultBanTtl,
            )
        }
    }
    environment.log.info(
        "Antifraud installed (ip=${if (rateLimitConfig.ipEnabled) "on" else "off"}, " +
            "subject=${if (rateLimitConfig.subjectEnabled) "on" else "off"}, " +
            "trustProxy=${if (rateLimitConfig.trustProxy) "on" else "off"})",
    )
}

private fun Application.loadConfig(): RateLimitConfig {
    val ipEnabled = readBoolean(ENV_IP_ENABLED, CONFIG_IP_ENABLED, default = true)
    val ipRps = readDouble(ENV_IP_RPS, CONFIG_IP_RPS, default = 100.0)
    val ipBurst = readInt(ENV_IP_BURST, CONFIG_IP_BURST, default = 20).coerceAtLeast(1)
    val ipTtl = readLong(ENV_IP_TTL, CONFIG_IP_TTL, default = 600L).coerceAtLeast(1L)
    val subjectEnabled = readBoolean(ENV_SUBJECT_ENABLED, CONFIG_SUBJECT_ENABLED, default = true)
    val subjectRps = readDouble(ENV_SUBJECT_RPS, CONFIG_SUBJECT_RPS, default = 60.0)
    val subjectBurst = readInt(ENV_SUBJECT_BURST, CONFIG_SUBJECT_BURST, default = 20).coerceAtLeast(1)
    val subjectTtl = readLong(ENV_SUBJECT_TTL, CONFIG_SUBJECT_TTL, default = 600L).coerceAtLeast(1L)
    val trustProxy = readBoolean(ENV_TRUST_PROXY, CONFIG_TRUST_PROXY, default = false)
    val includePaths =
        readString(ENV_INCLUDE_PATHS, CONFIG_INCLUDE_PATHS, default = "/telegram/webhook,/api/miniapp/invoice")
            .split(',')
            .mapNotNull { part -> part.trim().takeIf { it.isNotEmpty() } }
    val excludePaths =
        readString(ENV_EXCLUDE_PATHS, CONFIG_EXCLUDE_PATHS, default = "")
            .split(',')
            .mapNotNull { part -> part.trim().takeIf { it.isNotEmpty() } }
    val retryAfter = readLong(ENV_RETRY_AFTER, CONFIG_RETRY_AFTER, default = 60L).coerceAtLeast(0L)
    return RateLimitConfig(
        ipEnabled = ipEnabled,
        ipCapacity = ipBurst,
        ipRefillPerSec = ipRps,
        ipTtlSeconds = ipTtl,
        subjectEnabled = subjectEnabled,
        subjectCapacity = subjectBurst,
        subjectRefillPerSec = subjectRps,
        subjectTtlSeconds = subjectTtl,
        trustProxy = trustProxy,
        includePaths = includePaths,
        excludePaths = excludePaths,
        retryAfterFallbackSeconds = retryAfter,
    )
}

private fun Application.readAdminToken(): String? =
    readRaw(ENV_ADMIN_TOKEN, CONFIG_ADMIN_TOKEN)?.takeUnless { it.isBlank() }

private fun Application.readBanDefaultTtl(): Long =
    readLong(
        ENV_BAN_DEFAULT_TTL,
        CONFIG_BAN_DEFAULT_TTL,
        default = 86_400L,
        allowZero = true,
    )

private fun Application.readString(
    envKey: String,
    configKey: String,
    default: String,
): String = readRaw(envKey, configKey) ?: default

private fun Application.readBoolean(
    envKey: String,
    configKey: String,
    default: Boolean,
): Boolean {
    val raw = readRaw(envKey, configKey) ?: return default
    return when (raw.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> default
    }
}

private fun Application.readInt(
    envKey: String,
    configKey: String,
    default: Int,
): Int = readRaw(envKey, configKey)?.toIntOrNull()?.takeIf { it > 0 } ?: default

private fun Application.readLong(
    envKey: String,
    configKey: String,
    default: Long,
    allowZero: Boolean = false,
): Long {
    val parsed = readRaw(envKey, configKey)?.toLongOrNull() ?: return default
    return when {
        parsed < 0 -> default
        !allowZero && parsed == 0L -> default
        else -> parsed
    }
}

private fun Application.readDouble(
    envKey: String,
    configKey: String,
    default: Double,
): Double = readRaw(envKey, configKey)?.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: default

private fun Application.readRaw(
    envKey: String,
    configKey: String,
): String? {
    val envValue = System.getenv(envKey)?.takeUnless { it.isBlank() }
    if (envValue != null) {
        return envValue
    }
    val configEntry = environment.config.propertyOrNull(configKey)
    return configEntry?.getString()?.takeUnless { it.isBlank() }
}
