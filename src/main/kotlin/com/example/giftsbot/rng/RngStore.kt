package com.example.giftsbot.rng

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

interface RngCommitStore {
    fun upsertCommit(
        dayUtc: LocalDate,
        serverSeedHash: String,
    ): RngCommitState

    fun getCommit(dayUtc: LocalDate): RngCommitState?

    fun reveal(
        dayUtc: LocalDate,
        serverSeed: String,
    ): RngCommitState?

    fun latestCommitted(): RngCommitState?
}

interface RngDrawStore {
    @Suppress("LongParameterList")
    fun insertIfAbsent(
        caseId: String,
        userId: Long,
        nonce: String,
        serverSeedHash: String,
        rollHex: String,
        ppm: Int,
        resultItemId: String?,
    ): RngDrawRecord

    fun findByIdempotency(
        caseId: String,
        userId: Long,
        nonce: String,
    ): RngDrawRecord?

    fun listByUser(
        userId: Long,
        limit: Int,
        offset: Int,
    ): List<RngDrawRecord>
}

internal const val RNG_STORE_TTL_DAYS: Long = 30L
internal val RNG_STORE_DEFAULT_TTL: Duration = Duration.ofDays(RNG_STORE_TTL_DAYS)

@Serializable
sealed interface RngCommitState {
    val dayUtc: LocalDate
    val serverSeedHash: String
    val committedAt: Instant
}

@Serializable
@SerialName("pending")
data class RngCommitPending(
    @Serializable(with = LocalDateIso8601Serializer::class)
    override val dayUtc: LocalDate,
    override val serverSeedHash: String,
    @Serializable(with = InstantIso8601Serializer::class)
    override val committedAt: Instant,
) : RngCommitState

@Serializable
@SerialName("revealed")
data class RngCommitRevealed(
    @Serializable(with = LocalDateIso8601Serializer::class)
    override val dayUtc: LocalDate,
    override val serverSeedHash: String,
    @Serializable(with = InstantIso8601Serializer::class)
    override val committedAt: Instant,
    val serverSeed: String,
    @Serializable(with = InstantIso8601Serializer::class)
    val revealedAt: Instant,
) : RngCommitState

@Serializable
data class RngDrawRecord(
    val caseId: String,
    val userId: Long,
    val nonce: String,
    val serverSeedHash: String,
    val rollHex: String,
    val ppm: Int,
    val resultItemId: String?,
    @Serializable(with = InstantIso8601Serializer::class)
    val createdAt: Instant,
)

data class RngDrawIdempotencyKey(
    val caseId: String,
    val userId: Long,
    val nonce: String,
)

internal fun RngCommitPending.reveal(
    serverSeed: String,
    revealedAt: Instant,
): RngCommitRevealed =
    RngCommitRevealed(
        dayUtc = dayUtc,
        serverSeedHash = serverSeedHash,
        committedAt = committedAt,
        serverSeed = serverSeed,
        revealedAt = revealedAt,
    )

object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InstantIso8601", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }
}

object LocalDateIso8601Serializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDateIso8601", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: LocalDate,
    ) {
        encoder.encodeString(value.toString())
    }
}
