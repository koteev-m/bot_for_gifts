package com.example.giftsbot.rng

import com.example.app.observability.Metrics
import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.economy.PrizeItemConfig
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RngService(
    private val commitStore: RngCommitStore,
    private val drawStore: RngDrawStore,
    private val fairnessKey: ByteArray,
    private val casesRepository: CasesRepository,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val commitCounter = Metrics.counter(meterRegistry, COMMIT_METRIC)
    private val revealCounter = Metrics.counter(meterRegistry, REVEAL_METRIC)
    private val drawCounter = Metrics.counter(meterRegistry, DRAW_METRIC)
    private val drawIdempotentCounter = Metrics.counter(meterRegistry, DRAW_IDEMPOTENT_METRIC)
    private val drawFailCounter = Metrics.counter(meterRegistry, DRAW_FAIL_METRIC)

    fun ensureTodayCommit(): RngCommitState {
        val dayUtc = todayUtc()
        val serverSeedHash = Fairness.serverSeedHash(fairnessKey, dayUtc)
        return recordCommit(commitStore.upsertCommit(dayUtc, serverSeedHash))
    }

    fun reveal(dayUtc: LocalDate): RngCommitRevealed {
        require(dayUtc.isBefore(todayUtc())) { "Reveal is allowed only for past days" }
        val commit = commitStore.getCommit(dayUtc) ?: error("Commit for $dayUtc is not available")
        val expectedHash = Fairness.serverSeedHash(fairnessKey, dayUtc)
        require(commit.serverSeedHash == expectedHash) { "Stored hash mismatch for $dayUtc" }
        val serverSeed = toHex(Fairness.serverSeed(fairnessKey, dayUtc))
        val state = commitStore.reveal(dayUtc, serverSeed) ?: error("Commit for $dayUtc vanished during reveal")
        revealCounter.increment()
        return state as? RngCommitRevealed ?: error("Commit for $dayUtc is not revealed")
    }

    fun draw(
        caseId: String,
        userId: Long,
        nonce: String,
    ): RngDrawResult {
        val dayUtc = todayUtc()
        val serverSeed = Fairness.serverSeed(fairnessKey, dayUtc)
        val serverSeedHash = Fairness.serverSeedHash(fairnessKey, dayUtc)
        val commit = ensureCommit(dayUtc, serverSeedHash)
        val case = caseOrFail(caseId)
        val ppm = Fairness.rollPpm(serverSeed, userId, nonce, caseId)
        val rollHex = Fairness.rollHex(serverSeed, userId, nonce, caseId)
        val resultItemId = resolvePrize(case.items, ppm)
        val beforeInsert = clock.instant()
        val record =
            drawStore.insertIfAbsent(
                caseId = caseId,
                userId = userId,
                nonce = nonce,
                serverSeedHash = commit.serverSeedHash,
                rollHex = rollHex,
                ppm = ppm,
                resultItemId = resultItemId,
            )
        updateDrawCounters(beforeInsert, record)
        val receipt = Fairness.receiptFor(commit.serverSeedHash, serverSeed, userId, nonce, caseId, dayUtc)
        return RngDrawResult(record, receipt)
    }

    private fun recordCommit(state: RngCommitState): RngCommitState {
        commitCounter.increment()
        return state
    }

    private fun ensureCommit(
        dayUtc: LocalDate,
        serverSeedHash: String,
    ): RngCommitState {
        val existing = commitStore.getCommit(dayUtc)
        if (existing != null) {
            require(existing.serverSeedHash == serverSeedHash) { "Stored hash mismatch for $dayUtc" }
            return existing
        }
        val state = commitStore.upsertCommit(dayUtc, serverSeedHash)
        return recordCommit(state)
    }

    private fun caseOrFail(caseId: String): CaseConfig {
        val case = casesRepository.get(caseId)
        if (case != null) {
            return case
        }
        drawFailCounter.increment()
        error("Case '$caseId' is not available")
    }

    private fun resolvePrize(
        items: List<PrizeItemConfig>,
        ppm: Int,
    ): String? {
        var cumulative = 0
        for (item in items) {
            cumulative += item.probabilityPpm
            if (ppm < cumulative) {
                return item.id
            }
        }
        return null
    }

    private fun updateDrawCounters(
        beforeInsert: Instant,
        record: RngDrawRecord,
    ) {
        if (record.createdAt.isBefore(beforeInsert)) {
            drawIdempotentCounter.increment()
        } else {
            drawCounter.increment()
        }
    }

    private fun todayUtc(): LocalDate = clock.instant().atZone(ZoneOffset.UTC).toLocalDate()

    companion object {
        private const val COMMIT_METRIC = "rng_commit_total"
        private const val REVEAL_METRIC = "rng_reveal_total"
        private const val DRAW_METRIC = "rng_draw_total"
        private const val DRAW_IDEMPOTENT_METRIC = "rng_draw_idempotent_total"
        private const val DRAW_FAIL_METRIC = "rng_draw_fail_total"
    }
}

data class RngDrawResult(
    val record: RngDrawRecord,
    val receipt: RngReceipt,
)
