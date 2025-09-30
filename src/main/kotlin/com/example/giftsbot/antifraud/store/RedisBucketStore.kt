package com.example.giftsbot.antifraud.store

import com.example.giftsbot.antifraud.BucketState
import com.example.giftsbot.antifraud.BucketStore
import com.example.giftsbot.antifraud.Clock

/**
 * Redis-backed implementation contract for [BucketStore].
 *
 * The future implementation must use a single Lua script that receives key, now, capacity,
 * refill rate, TTL, cost, and initial tokens, and returns tokens, updated, expires, allowed,
 * remaining, retryAfter, and resetAt. The script has to refresh key TTL via EXPIRE on every
 * compute call and remain idempotent within its execution.
 */
class RedisBucketStore : BucketStore {
    override fun <T> compute(
        key: String,
        ttlSeconds: Long,
        clock: Clock,
        block: (current: BucketState?, nowMillis: Long) -> Pair<BucketState, T>,
    ): T =
        throw UnsupportedOperationException(
            "RedisBucketStore is not implemented yet. Contract: atomic compute per key via Lua/EVALSHA; " +
                "must apply TTL, refill, cost deduction, and return decision together with new state.",
        )
}
