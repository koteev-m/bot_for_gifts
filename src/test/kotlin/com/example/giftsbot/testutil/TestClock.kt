package com.example.giftsbot.testutil

import com.example.giftsbot.antifraud.Clock as RateLimitClock
import com.example.giftsbot.antifraud.velocity.Clock as VelocityClock

class TestClock(
    startMs: Long = 0L,
) : RateLimitClock,
    VelocityClock {
    private var current: Long = startMs

    override fun nowMillis(): Long = current

    fun advanceMs(delta: Long) {
        require(delta >= 0) { "delta must be non-negative" }
        current += delta
    }
}
