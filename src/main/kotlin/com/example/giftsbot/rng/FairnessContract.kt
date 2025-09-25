package com.example.giftsbot.rng

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private const val ROLL_PREFIX_LENGTH = 8
private const val SHIFT_BITS = 64
private const val PPM_SCALE_FACTOR = 1_000_000L
private val TWO_POW_64: BigInteger = BigInteger.ONE.shiftLeft(SHIFT_BITS)
private val PPM_SCALE: BigInteger = BigInteger.valueOf(PPM_SCALE_FACTOR)
private const val CLIENT_SEED_SUFFIX = "v1"

data class RngReceipt(
    val date: LocalDate,
    val serverSeedHash: String,
    val clientSeed: String,
    val rollHex: String,
    val ppm: Int,
)

object Fairness {
    fun serverSeed(
        fairnessKey: ByteArray,
        dayUtc: LocalDate,
    ): ByteArray {
        val dateBytes = DATE_FORMATTER.format(dayUtc).toByteArray(StandardCharsets.UTF_8)
        return hmacSha256(fairnessKey, dateBytes)
    }

    fun serverSeedHash(
        fairnessKey: ByteArray,
        dayUtc: LocalDate,
    ): String {
        val seed = serverSeed(fairnessKey, dayUtc)
        return toHex(sha256(seed))
    }

    fun rollPpm(
        serverSeed: ByteArray,
        userId: Long,
        nonce: String,
        caseId: String,
    ): Int {
        val rollBytes = rollBytes(serverSeed, userId, nonce, caseId)
        return ppmFromRollBytes(rollBytes)
    }

    fun rollHex(
        serverSeed: ByteArray,
        userId: Long,
        nonce: String,
        caseId: String,
    ): String {
        val rollBytes = rollBytes(serverSeed, userId, nonce, caseId)
        return toHex(rollBytes)
    }

    @Suppress("LongParameterList")
    fun receiptFor(
        serverSeedHash: String,
        serverSeed: ByteArray,
        userId: Long,
        nonce: String,
        caseId: String,
        dayUtc: LocalDate,
    ): RngReceipt {
        val clientSeed = clientSeed(userId, nonce, caseId)
        val rollBytes = rollBytes(serverSeed, userId, nonce, caseId)
        val rollHex = toHex(rollBytes)
        val ppm = ppmFromRollBytes(rollBytes)
        return RngReceipt(
            date = dayUtc,
            serverSeedHash = serverSeedHash,
            clientSeed = clientSeed,
            rollHex = rollHex,
            ppm = ppm,
        )
    }

    private fun rollBytes(
        serverSeed: ByteArray,
        userId: Long,
        nonce: String,
        caseId: String,
    ): ByteArray {
        val seed = clientSeed(userId, nonce, caseId)
        val seedBytes = seed.toByteArray(StandardCharsets.UTF_8)
        return hmacSha256(serverSeed, seedBytes)
    }

    private fun ppmFromRollBytes(rollBytes: ByteArray): Int =
        BigInteger(1, rollBytes.copyOfRange(0, ROLL_PREFIX_LENGTH))
            .multiply(PPM_SCALE)
            .divide(TWO_POW_64)
            .toInt()

    private fun clientSeed(
        userId: Long,
        nonce: String,
        caseId: String,
    ): String =
        buildString {
            append(userId)
            append('|')
            append(nonce)
            append('|')
            append(caseId)
            append('|')
            append(CLIENT_SEED_SUFFIX)
        }
}
