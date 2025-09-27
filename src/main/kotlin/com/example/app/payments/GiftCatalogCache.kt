package com.example.app.payments

import com.example.giftsbot.telegram.GiftDto
import com.example.giftsbot.telegram.TelegramApiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class GiftCatalogCache(
    private val telegramApiClient: TelegramApiClient,
    private val ttl: Duration = DEFAULT_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    @Volatile
    private var state: CacheState? = null

    suspend fun getGifts(): List<GiftDto> {
        if (ttl.isZero || ttl.isNegative) {
            return telegramApiClient.getAvailableGifts().gifts
        }
        val now = clock.instant()
        val cached = state
        if (cached != null && now.isBefore(cached.expiresAt)) {
            return cached.gifts
        }
        return mutex.withLock {
            val refreshed = state
            val refreshedNow = clock.instant()
            if (refreshed != null && refreshedNow.isBefore(refreshed.expiresAt)) {
                return@withLock refreshed.gifts
            }
            val response = telegramApiClient.getAvailableGifts()
            val updated = CacheState(response.gifts, refreshedNow.plus(ttl))
            state = updated
            updated.gifts
        }
    }

    private data class CacheState(
        val gifts: List<GiftDto>,
        val expiresAt: Instant,
    )

    companion object {
        private val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
    }
}
