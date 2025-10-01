package com.example.giftsbot.antifraud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class IpStatus {
    SUSPICIOUS,
    TEMP_BANNED,
    PERM_BANNED,
}

@Serializable
data class IpEntry(
    val ip: String,
    val status: IpStatus,
    val reason: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val lastSeenMs: Long,
)

interface SuspiciousIpStore {
    fun markSuspicious(
        ip: String,
        reason: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): IpEntry

    fun ban(
        ip: String,
        ttlSeconds: Long?,
        reason: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): IpEntry

    fun unban(
        ip: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean

    fun isBanned(
        ip: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Boolean, Long?>

    fun listRecent(
        limit: Int = 100,
        sinceMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<IpEntry>

    fun listBanned(
        limit: Int = 100,
        nowMs: Long = System.currentTimeMillis(),
    ): List<IpEntry>
}

private const val MAX_LIST_LIMIT = 100
private const val MIN_LIST_LIMIT = 1
private const val MILLIS_IN_SECOND = 1000L
private const val PERMANENT_TTL_SECONDS = 0L

class InMemorySuspiciousIpStore : SuspiciousIpStore {
    private val entries = ConcurrentHashMap<String, IpEntry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override fun markSuspicious(
        ip: String,
        reason: String?,
        nowMs: Long,
    ): IpEntry =
        withIpLock(ip) {
            val existing = activeEntry(ip, nowMs)
            val createdAt = existing?.createdAtMs ?: nowMs
            val updatedReason = reason ?: existing?.reason
            val entry =
                IpEntry(
                    ip = ip,
                    status = IpStatus.SUSPICIOUS,
                    reason = updatedReason,
                    createdAtMs = createdAt,
                    expiresAtMs = null,
                    lastSeenMs = nowMs,
                )
            entries[ip] = entry
            entry
        }

    override fun ban(
        ip: String,
        ttlSeconds: Long?,
        reason: String?,
        nowMs: Long,
    ): IpEntry =
        withIpLock(ip) {
            val existing = activeEntry(ip, nowMs)
            val createdAt = existing?.createdAtMs ?: nowMs
            val sanitizedTtl = (ttlSeconds ?: PERMANENT_TTL_SECONDS).coerceAtLeast(PERMANENT_TTL_SECONDS)
            val status = if (sanitizedTtl == PERMANENT_TTL_SECONDS) IpStatus.PERM_BANNED else IpStatus.TEMP_BANNED
            val expiresAt = if (status == IpStatus.TEMP_BANNED) nowMs + sanitizedTtl * MILLIS_IN_SECOND else null
            val entry =
                IpEntry(
                    ip = ip,
                    status = status,
                    reason = reason ?: existing?.reason,
                    createdAtMs = createdAt,
                    expiresAtMs = expiresAt,
                    lastSeenMs = nowMs,
                )
            entries[ip] = entry
            entry
        }

    override fun unban(
        ip: String,
        nowMs: Long,
    ): Boolean =
        withIpLock(ip) {
            val existing = entries[ip]
            if (existing == null) {
                return@withIpLock false
            }
            if (isExpired(existing, nowMs)) {
                entries.remove(ip)
                return@withIpLock true
            }
            entries.remove(ip)
            true
        }

    override fun isBanned(
        ip: String,
        nowMs: Long,
    ): Pair<Boolean, Long?> =
        withIpLock(ip) {
            val entry = activeEntry(ip, nowMs) ?: return@withIpLock false to null
            val updated = entry.copy(lastSeenMs = nowMs)
            entries[ip] = updated
            when (updated.status) {
                IpStatus.PERM_BANNED -> true to null
                IpStatus.TEMP_BANNED -> true to updated.remainingSeconds(nowMs)
                IpStatus.SUSPICIOUS -> false to null
            }
        }

    override fun listRecent(
        limit: Int,
        sinceMs: Long?,
        nowMs: Long,
    ): List<IpEntry> {
        cleanupExpired(nowMs)
        val effectiveLimit = limit.coerceIn(MIN_LIST_LIMIT, MAX_LIST_LIMIT)
        return entries
            .values
            .asSequence()
            .filter { entry -> sinceMs == null || entry.createdAtMs >= sinceMs }
            .sortedByDescending { entry -> entry.createdAtMs }
            .take(effectiveLimit)
            .toList()
    }

    override fun listBanned(
        limit: Int,
        nowMs: Long,
    ): List<IpEntry> {
        cleanupExpired(nowMs)
        val effectiveLimit = limit.coerceIn(MIN_LIST_LIMIT, MAX_LIST_LIMIT)
        val activeBans =
            entries.values.filter { entry ->
                when (entry.status) {
                    IpStatus.TEMP_BANNED -> entry.expiresAtMs != null
                    IpStatus.PERM_BANNED -> true
                    IpStatus.SUSPICIOUS -> false
                }
            }
        val sortedTemporary =
            activeBans
                .asSequence()
                .filter { entry -> entry.status == IpStatus.TEMP_BANNED }
                .sortedBy { entry -> entry.expiresAtMs }
                .toList()
        val sortedPermanent =
            activeBans
                .asSequence()
                .filter { entry -> entry.status == IpStatus.PERM_BANNED }
                .sortedBy { entry -> entry.createdAtMs }
                .toList()
        return (sortedTemporary + sortedPermanent).take(effectiveLimit)
    }

    private fun cleanupExpired(nowMs: Long) {
        entries.entries.removeIf { (_, entry) -> isExpired(entry, nowMs) }
    }

    private fun activeEntry(
        ip: String,
        nowMs: Long,
    ): IpEntry? {
        val entry = entries[ip]
        return when {
            entry == null -> null
            isExpired(entry, nowMs) -> {
                entries.remove(ip)
                null
            }
            else -> entry
        }
    }

    private fun isExpired(
        entry: IpEntry,
        nowMs: Long,
    ): Boolean =
        when (entry.status) {
            IpStatus.TEMP_BANNED -> entry.expiresAtMs?.let { expiresAt -> expiresAt <= nowMs } ?: true
            IpStatus.PERM_BANNED, IpStatus.SUSPICIOUS -> false
        }

    private fun <T> withIpLock(
        ip: String,
        action: () -> T,
    ): T {
        val mutex = locks.computeIfAbsent(ip) { Mutex() }
        return runBlocking {
            val result = mutex.withLock { action() }
            if (!mutex.isLocked && !entries.containsKey(ip)) {
                locks.remove(ip, mutex)
            }
            result
        }
    }
}

private fun IpEntry.remainingSeconds(nowMs: Long): Long? {
    val expiresAt = expiresAtMs ?: return null
    val remaining = expiresAt - nowMs
    return when {
        remaining <= 0 -> 0L
        else -> {
            val quotient = remaining / MILLIS_IN_SECOND
            val remainder = remaining % MILLIS_IN_SECOND
            if (remainder == 0L) quotient else quotient + 1
        }
    }
}
