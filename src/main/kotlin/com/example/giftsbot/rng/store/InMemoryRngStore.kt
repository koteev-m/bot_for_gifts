package com.example.giftsbot.rng.store

import com.example.giftsbot.rng.RNG_STORE_DEFAULT_TTL
import com.example.giftsbot.rng.RngCommitPending
import com.example.giftsbot.rng.RngCommitState
import com.example.giftsbot.rng.RngCommitStore
import com.example.giftsbot.rng.RngDrawIdempotencyKey
import com.example.giftsbot.rng.RngDrawRecord
import com.example.giftsbot.rng.RngDrawStore
import com.example.giftsbot.rng.reveal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("TooManyFunctions")
open class InMemoryRngStore(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = RNG_STORE_DEFAULT_TTL,
) : RngCommitStore,
    RngDrawStore {
    protected val commitMap: ConcurrentHashMap<LocalDate, RngCommitState> = ConcurrentHashMap()
    protected val drawMap: ConcurrentHashMap<RngDrawIdempotencyKey, RngDrawRecord> = ConcurrentHashMap()
    protected val drawsByUser: ConcurrentHashMap<Long, MutableList<RngDrawRecord>> = ConcurrentHashMap()

    override fun upsertCommit(
        dayUtc: LocalDate,
        serverSeedHash: String,
    ): RngCommitState {
        val now = clock.instant()
        cleanupExpiredState(now)
        var changed = false
        val updated =
            commitMap.compute(dayUtc) { _, existing ->
                when (existing) {
                    null -> {
                        changed = true
                        RngCommitPending(dayUtc, serverSeedHash, now)
                    }
                    is RngCommitPending -> {
                        if (existing.serverSeedHash == serverSeedHash) {
                            existing
                        } else {
                            changed = true
                            existing.copy(serverSeedHash = serverSeedHash, committedAt = now)
                        }
                    }
                    else -> existing
                }
            }
        if (changed) {
            afterCommitsChanged()
        }
        return requireNotNull(updated)
    }

    override fun getCommit(dayUtc: LocalDate): RngCommitState? {
        cleanupExpiredState()
        return commitMap[dayUtc]
    }

    override fun reveal(
        dayUtc: LocalDate,
        serverSeed: String,
    ): RngCommitState? {
        val now = clock.instant()
        cleanupExpiredState(now)
        var changed = false
        val updated =
            commitMap.computeIfPresent(dayUtc) { _, existing ->
                when (existing) {
                    is RngCommitPending -> {
                        changed = true
                        existing.reveal(serverSeed, now)
                    }
                    else -> existing
                }
            }
        if (changed) {
            afterCommitsChanged()
        }
        return updated
    }

    override fun latestCommitted(): RngCommitState? {
        cleanupExpiredState()
        return commitMap.values.maxByOrNull { it.dayUtc }
    }

    override fun insertIfAbsent(
        caseId: String,
        userId: Long,
        nonce: String,
        serverSeedHash: String,
        rollHex: String,
        ppm: Int,
        resultItemId: String?,
    ): RngDrawRecord {
        val now = clock.instant()
        cleanupExpiredState(now)
        val key = RngDrawIdempotencyKey(caseId, userId, nonce)
        val record =
            RngDrawRecord(
                caseId = caseId,
                userId = userId,
                nonce = nonce,
                serverSeedHash = serverSeedHash,
                rollHex = rollHex,
                ppm = ppm,
                resultItemId = resultItemId,
                createdAt = now,
            )
        val existing = drawMap.putIfAbsent(key, record)
        if (existing != null) {
            return existing
        }
        val drawsForUser = drawsByUser.computeIfAbsent(userId) { CopyOnWriteArrayList() }
        drawsForUser.add(record)
        afterDrawInserted(record)
        return record
    }

    override fun findByIdempotency(
        caseId: String,
        userId: Long,
        nonce: String,
    ): RngDrawRecord? {
        cleanupExpiredState()
        return drawMap[RngDrawIdempotencyKey(caseId, userId, nonce)]
    }

    override fun listByUser(
        userId: Long,
        limit: Int,
        offset: Int,
    ): List<RngDrawRecord> {
        require(limit >= 0) { "limit must be non-negative" }
        require(offset >= 0) { "offset must be non-negative" }
        cleanupExpiredState()
        val draws = drawsByUser[userId]?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val sorted = draws.sortedByDescending { it.createdAt }
        return if (limit == 0 || offset >= sorted.size) {
            emptyList()
        } else {
            sorted.drop(offset).take(limit)
        }
    }

    protected open fun afterCommitsChanged() {
        // no-op for in-memory variant
    }

    protected open fun afterDrawInserted(record: RngDrawRecord) {
        // no-op for in-memory variant
    }

    protected fun commitSnapshot(): List<RngCommitState> = commitMap.values.sortedBy { it.dayUtc }

    protected fun idempotencyKey(record: RngDrawRecord): RngDrawIdempotencyKey =
        RngDrawIdempotencyKey(record.caseId, record.userId, record.nonce)

    protected fun restoreCommit(state: RngCommitState) {
        val now = clock.instant()
        if (state.committedAt.isBefore(now.minus(ttl))) {
            return
        }
        commitMap[state.dayUtc] = state
    }

    protected fun restoreDraw(record: RngDrawRecord) {
        val now = clock.instant()
        if (record.createdAt.isBefore(now.minus(ttl))) {
            return
        }
        val key = idempotencyKey(record)
        if (drawMap.putIfAbsent(key, record) == null) {
            val list = drawsByUser.computeIfAbsent(record.userId) { CopyOnWriteArrayList() }
            list.add(record)
        }
    }

    protected fun cleanupExpiredState(now: Instant = clock.instant()) {
        cleanupExpired(now)
    }

    private fun cleanupExpired(now: Instant) {
        cleanupExpiredCommits(now)
        cleanupExpiredDraws(now)
    }

    private fun cleanupExpiredCommits(now: Instant) {
        val cutoff = now.minus(ttl)
        commitMap.entries.forEach { (day, commit) ->
            if (commit.committedAt.isBefore(cutoff)) {
                commitMap.remove(day, commit)
            }
        }
    }

    private fun cleanupExpiredDraws(now: Instant) {
        val cutoff = now.minus(ttl)
        drawMap.entries.forEach { (key, record) ->
            if (record.createdAt.isBefore(cutoff)) {
                drawMap.remove(key, record)
            }
        }
        drawsByUser.entries.forEach { (userId, records) ->
            records.removeIf { it.createdAt.isBefore(cutoff) }
            if (records.isEmpty()) {
                drawsByUser.remove(userId, records)
            }
        }
    }
}
