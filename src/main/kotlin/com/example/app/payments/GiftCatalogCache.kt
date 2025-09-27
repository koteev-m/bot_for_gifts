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

    suspend fun getGifts(): List<GiftDto> =
        when {
            ttl.isZero || ttl.isNegative -> fetchFresh()
            else -> readFromCache() ?: refreshCache()
        }

    private suspend fun readFromCache(): List<GiftDto>? {
        val now = clock.instant()
        return state?.takeIf { now.isBefore(it.expiresAt) }?.gifts
    }

    private suspend fun refreshCache(): List<GiftDto> =
        mutex.withLock {
            val cached = readFromCache()
            if (cached != null) {
                return@withLock cached
            }
            val fetchedAt = clock.instant()
            val response = telegramApiClient.getAvailableGifts()
            val updated = CacheState(response.gifts, fetchedAt.plus(ttl))
            state = updated
            updated.gifts
        }

    private suspend fun fetchFresh(): List<GiftDto> = telegramApiClient.getAvailableGifts().gifts

    private data class CacheState(
        val gifts: List<GiftDto>,
        val expiresAt: Instant,
    )

    companion object {
        private val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
    }
}
