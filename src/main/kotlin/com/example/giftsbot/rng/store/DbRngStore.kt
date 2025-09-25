package com.example.giftsbot.rng.store

import com.example.giftsbot.rng.RngCommitPending
import com.example.giftsbot.rng.RngCommitState
import com.example.giftsbot.rng.RngDrawRecord
import com.example.giftsbot.rng.RngStore
import com.example.giftsbot.rng.reveal
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.StatementContext
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

@Suppress("TooManyFunctions")
class DbRngStore(
    private val jdbi: Jdbi,
) : RngStore {
    override fun upsertCommit(
        dayUtc: LocalDate,
        serverSeedHash: String,
    ): RngCommitState =
        jdbi.inTransaction<RngCommitState, Exception> { handle ->
            handle.insertCommit(dayUtc, serverSeedHash)
            handle.findCommit(dayUtc) ?: error("Commit for $dayUtc is missing")
        }

    override fun getCommit(dayUtc: LocalDate): RngCommitState? =
        jdbi.withHandle<RngCommitState?, Exception> { handle ->
            handle.findCommit(dayUtc)
        }

    override fun reveal(
        dayUtc: LocalDate,
        serverSeed: String,
    ): RngCommitState? =
        jdbi.inTransaction<RngCommitState?, Exception> { handle ->
            val updated = handle.revealCommit(dayUtc, serverSeed)
            if (updated) {
                handle.findCommit(dayUtc) ?: error("Commit for $dayUtc is missing")
            } else {
                handle.findCommit(dayUtc)
            }
        }

    override fun latestCommitted(): RngCommitState? =
        jdbi.withHandle<RngCommitState?, Exception> { handle ->
            handle.findLatestCommit()
        }

    @Suppress("LongParameterList")
    override fun insertIfAbsent(
        caseId: String,
        userId: Long,
        nonce: String,
        serverSeedHash: String,
        rollHex: String,
        ppm: Int,
        resultItemId: String?,
    ): RngDrawRecord =
        jdbi.inTransaction<RngDrawRecord, Exception> { handle ->
            handle.insertDraw(caseId, userId, nonce, serverSeedHash, rollHex, ppm, resultItemId)
            handle.findDraw(caseId, userId, nonce) ?: error("Draw record is missing")
        }

    override fun findByIdempotency(
        caseId: String,
        userId: Long,
        nonce: String,
    ): RngDrawRecord? =
        jdbi.withHandle<RngDrawRecord?, Exception> { handle ->
            handle.findDraw(caseId, userId, nonce)
        }

    override fun listByUser(
        userId: Long,
        limit: Int,
        offset: Int,
    ): List<RngDrawRecord> {
        require(limit >= 0) { "limit must be non-negative" }
        require(offset >= 0) { "offset must be non-negative" }
        return jdbi.withHandle<List<RngDrawRecord>, Exception> { handle ->
            handle.listDrawsByUser(userId, limit, offset)
        }
    }
}

private data class DbCommit(
    val dayUtc: LocalDate,
    val serverSeedHash: String,
    val committedAt: Instant,
    val revealedAt: Instant?,
    val serverSeed: String?,
)

private fun Handle.insertCommit(
    dayUtc: LocalDate,
    serverSeedHash: String,
) {
    createUpdate(
        """
        INSERT INTO rng_seed_commits(day_utc, server_seed_hash)
        VALUES (:dayUtc, :serverSeedHash)
        ON CONFLICT (day_utc) DO NOTHING
        """.trimIndent(),
    ).bind("dayUtc", dayUtc)
        .bind("serverSeedHash", serverSeedHash)
        .execute()
}

private fun Handle.revealCommit(
    dayUtc: LocalDate,
    serverSeed: String,
): Boolean =
    createUpdate(
        """
        UPDATE rng_seed_commits
        SET server_seed = :serverSeed, revealed_at = now()
        WHERE day_utc = :dayUtc AND server_seed IS NULL
        """.trimIndent(),
    ).bind("dayUtc", dayUtc)
        .bind("serverSeed", serverSeed)
        .execute() > 0

@Suppress("LongParameterList")
private fun Handle.insertDraw(
    caseId: String,
    userId: Long,
    nonce: String,
    serverSeedHash: String,
    rollHex: String,
    ppm: Int,
    resultItemId: String?,
) {
    createUpdate(
        """
        INSERT INTO rng_draws(
            case_id,
            user_id,
            nonce,
            server_seed_hash,
            roll_hex,
            ppm,
            result_item_id
        ) VALUES (:caseId, :userId, :nonce, :serverSeedHash, :rollHex, :ppm, :resultItemId)
        ON CONFLICT (case_id, user_id, nonce) DO NOTHING
        """.trimIndent(),
    ).bind("caseId", caseId)
        .bind("userId", userId)
        .bind("nonce", nonce)
        .bind("serverSeedHash", serverSeedHash)
        .bind("rollHex", rollHex)
        .bind("ppm", ppm)
        .bind("resultItemId", resultItemId)
        .execute()
}

private fun Handle.findCommit(dayUtc: LocalDate): RngCommitState? =
    createQuery(
        """
        SELECT day_utc, server_seed_hash, committed_at, revealed_at, server_seed
        FROM rng_seed_commits
        WHERE day_utc = :dayUtc
        """.trimIndent(),
    ).bind("dayUtc", dayUtc)
        .map(::mapCommit)
        .list()
        .singleOrNull()
        ?.toDomain()

private fun Handle.findLatestCommit(): RngCommitState? =
    createQuery(
        """
        SELECT day_utc, server_seed_hash, committed_at, revealed_at, server_seed
        FROM rng_seed_commits
        ORDER BY committed_at DESC
        LIMIT 1
        """.trimIndent(),
    ).map(::mapCommit)
        .list()
        .singleOrNull()
        ?.toDomain()

private fun Handle.findDraw(
    caseId: String,
    userId: Long,
    nonce: String,
): RngDrawRecord? =
    createQuery(
        """
        SELECT case_id, user_id, nonce, server_seed_hash, roll_hex, ppm, result_item_id, created_at
        FROM rng_draws
        WHERE case_id = :caseId AND user_id = :userId AND nonce = :nonce
        """.trimIndent(),
    ).bind("caseId", caseId)
        .bind("userId", userId)
        .bind("nonce", nonce)
        .map(::mapDraw)
        .list()
        .singleOrNull()

private fun Handle.listDrawsByUser(
    userId: Long,
    limit: Int,
    offset: Int,
): List<RngDrawRecord> =
    createQuery(
        """
        SELECT case_id, user_id, nonce, server_seed_hash, roll_hex, ppm, result_item_id, created_at
        FROM rng_draws
        WHERE user_id = :userId
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """.trimIndent(),
    ).bind("userId", userId)
        .bind("limit", limit)
        .bind("offset", offset)
        .map(::mapDraw)
        .list()

@Suppress("UnusedParameter")
private fun mapCommit(
    resultSet: ResultSet,
    context: StatementContext,
): DbCommit =
    DbCommit(
        dayUtc = resultSet.getObject("day_utc", LocalDate::class.java),
        serverSeedHash = resultSet.getString("server_seed_hash"),
        committedAt = resultSet.getObject("committed_at", OffsetDateTime::class.java).toInstant(),
        revealedAt = resultSet.getObject("revealed_at", OffsetDateTime::class.java)?.toInstant(),
        serverSeed = resultSet.getString("server_seed"),
    )

private fun DbCommit.toDomain(): RngCommitState =
    if (serverSeed != null && revealedAt != null) {
        RngCommitPending(dayUtc, serverSeedHash, committedAt).reveal(serverSeed, revealedAt)
    } else {
        RngCommitPending(dayUtc, serverSeedHash, committedAt)
    }

@Suppress("UnusedParameter")
private fun mapDraw(
    resultSet: ResultSet,
    context: StatementContext,
): RngDrawRecord =
    RngDrawRecord(
        caseId = resultSet.getString("case_id"),
        userId = resultSet.getLong("user_id"),
        nonce = resultSet.getString("nonce"),
        serverSeedHash = resultSet.getString("server_seed_hash"),
        rollHex = resultSet.getString("roll_hex"),
        ppm = resultSet.getInt("ppm"),
        resultItemId = resultSet.getString("result_item_id"),
        createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )
