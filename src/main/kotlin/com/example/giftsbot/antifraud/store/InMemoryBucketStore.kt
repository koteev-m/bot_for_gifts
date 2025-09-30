package com.example.giftsbot.antifraud.store

import com.example.giftsbot.antifraud.BucketState
import com.example.giftsbot.antifraud.BucketStore
import com.example.giftsbot.antifraud.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class InMemoryBucketStore : BucketStore {
    private val states = ConcurrentHashMap<String, BucketState>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Suppress("UNUSED_PARAMETER")
    override fun <T> compute(
        key: String,
        ttlSeconds: Long,
        clock: Clock,
        block: (current: BucketState?, nowMillis: Long) -> Pair<BucketState, T>,
    ): T {
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return runBlocking {
            mutex.withLock {
                val now = clock.nowMillis()
                val current = states[key]
                val (newState, result) = block(current, now)
                if (newState.expiresAtMillis <= now) {
                    states.remove(key)
                } else {
                    states[key] = newState
                }
                result
            }
        }
    }
}
