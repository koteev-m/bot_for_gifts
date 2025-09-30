package com.example.giftsbot.antifraud

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

sealed interface RateLimitKey {
    fun asString(): String
}

data class IpKey(
    val ip: String,
) : RateLimitKey {
    override fun asString(): String = "ip:$ip"
}

data class SubjectKey(
    val userId: Long,
) : RateLimitKey {
    override fun asString(): String = "sub:$userId"
}

data class PathKey(
    val path: String,
) : RateLimitKey {
    override fun asString(): String = "path:$path"
}

data class CompositeKey(
    val namespace: String,
    val value: String,
) : RateLimitKey {
    override fun asString(): String = "ns:$namespace:$value"
}

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

data class BucketParams(
    val capacity: Int,
    val refillTokensPerSecond: Double,
    val ttlSeconds: Long,
    val initialTokens: Int = capacity,
) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(ttlSeconds > 0) { "ttlSeconds must be > 0" }
        require(refillTokensPerSecond >= 0) { "refillTokensPerSecond must be >= 0" }
        require(initialTokens in 0..capacity) { "initialTokens must be within [0, capacity]" }
    }
}

data class BucketState(
    val tokens: Double,
    val updatedAtMillis: Long,
    val expiresAtMillis: Long,
)

data class RateLimitDecision(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterSeconds: Long?,
    val resetAtMillis: Long,
)

interface BucketStore {
    fun <T> compute(
        key: String,
        ttlSeconds: Long,
        clock: Clock,
        block: (current: BucketState?, nowMillis: Long) -> Pair<BucketState, T>,
    ): T
}

class TokenBucket(
    private val store: BucketStore,
    private val clock: Clock = SystemClock,
) {
    private val maxRetrySeconds: Long = Int.MAX_VALUE.toLong()
    private val resetFallbackMillis: Long = DAYS_IN_YEAR * SECONDS_IN_DAY * MILLIS_IN_SECOND

    fun tryConsume(
        key: RateLimitKey,
        params: BucketParams,
        cost: Int = 1,
    ): RateLimitDecision {
        require(cost >= 0) { "cost must be >= 0" }
        return store.compute(
            key = key.asString(),
            ttlSeconds = params.ttlSeconds,
            clock = clock,
        ) { current, now ->
            val tokens = resolveTokens(current, now, params)
            val (decision, updatedTokens) = evaluateDecision(tokens, params, cost, now)
            val expiresAt = now + params.ttlSeconds * MILLIS_IN_SECOND
            val newState = BucketState(tokens = updatedTokens, updatedAtMillis = now, expiresAtMillis = expiresAt)
            newState to decision
        }
    }

    private fun resolveTokens(
        current: BucketState?,
        now: Long,
        params: BucketParams,
    ): Double {
        val initial = params.initialTokens.toDouble()
        val existing = current
        if (existing == null || now > existing.expiresAtMillis) {
            return initial
        }
        val elapsedMillis = max(0L, now - existing.updatedAtMillis)
        val capacity = params.capacity.toDouble()
        val baseTokens =
            when {
                elapsedMillis == 0L || params.refillTokensPerSecond == 0.0 -> existing.tokens
                else -> {
                    val elapsedSeconds = elapsedMillis.toDouble() / MILLIS_IN_SECOND
                    existing.tokens + elapsedSeconds * params.refillTokensPerSecond
                }
            }
        return min(capacity, baseTokens)
    }

    private fun evaluateDecision(
        tokens: Double,
        params: BucketParams,
        cost: Int,
        now: Long,
    ): Pair<RateLimitDecision, Double> {
        val costDouble = cost.toDouble()
        val refillRate = params.refillTokensPerSecond
        val resultingTokens =
            when {
                cost > params.capacity -> tokens
                tokens >= costDouble -> tokens - costDouble
                else -> tokens
            }
        val allowed = cost <= params.capacity && tokens >= costDouble
        val retryAfterSeconds =
            if (allowed) {
                null
            } else {
                computeRetryAfter(costDouble - tokens, refillRate)
            }
        val safeTokens = max(resultingTokens, 0.0)
        val remaining = floor(safeTokens).toInt().coerceAtLeast(0)
        val resetAtMillis = computeResetAt(now, params.capacity.toDouble(), safeTokens, refillRate)
        val decision =
            RateLimitDecision(
                allowed = allowed,
                remaining = remaining,
                retryAfterSeconds = retryAfterSeconds,
                resetAtMillis = resetAtMillis,
            )
        return decision to safeTokens
    }

    private fun computeRetryAfter(
        need: Double,
        refillRate: Double,
    ): Long =
        when {
            need <= 0.0 -> 0
            refillRate <= 0.0 -> maxRetrySeconds
            else -> {
                val seconds = ceil(need / refillRate).toLong()
                seconds.coerceAtLeast(1).coerceAtMost(maxRetrySeconds)
            }
        }

    private fun computeResetAt(
        now: Long,
        capacity: Double,
        tokens: Double,
        refillRate: Double,
    ): Long =
        when {
            capacity <= tokens -> now
            refillRate <= 0.0 -> now + resetFallbackMillis
            else -> {
                val deficit = max(0.0, capacity - tokens)
                val seconds = ceil(deficit / refillRate).toLong().coerceAtLeast(0)
                val cappedSeconds = seconds.coerceAtMost(maxRetrySeconds)
                now + cappedSeconds * MILLIS_IN_SECOND
            }
        }

    private companion object {
        private const val MILLIS_IN_SECOND: Long = 1000L
        private const val SECONDS_IN_DAY: Long = 24L * 3600L
        private const val DAYS_IN_YEAR: Long = 365L
    }
}
