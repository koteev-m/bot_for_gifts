package com.example.giftsbot.rng

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FairnessContractTest {
    private val fairnessKeyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val fairnessKey: ByteArray = fromHex(fairnessKeyHex)

    private val userId = 42L
    private val nonce = "nonce-1"
    private val caseId = "case-id"

    private val dayUtc = LocalDate.of(2024, 6, 6)

    @Test
    fun `serverSeedHash is deterministic per day`() {
        val hash1 = Fairness.serverSeedHash(fairnessKey, dayUtc)
        val hash2 = Fairness.serverSeedHash(fairnessKey, dayUtc)

        assertAll(
            { assertEquals(hash1, hash2, "hash should be stable for the same day") },
            { assertEquals(64, hash1.length, "SHA-256 hash should be 64 hex chars") },
            { assertTrue(hash1.matches(HEX_REGEX), "hash should contain only hex characters") },
        )
    }

    @Test
    fun `roll values are deterministic for identical seeds`() {
        val serverSeed = Fairness.serverSeed(fairnessKey, dayUtc)

        val ppm1 = Fairness.rollPpm(serverSeed, userId, nonce, caseId)
        val ppm2 = Fairness.rollPpm(serverSeed, userId, nonce, caseId)
        val rollHex1 = Fairness.rollHex(serverSeed, userId, nonce, caseId)
        val rollHex2 = Fairness.rollHex(serverSeed, userId, nonce, caseId)

        assertAll(
            { assertEquals(ppm1, ppm2, "ppm should be stable for the same inputs") },
            { assertEquals(rollHex1, rollHex2, "roll hex should be stable for the same inputs") },
            { assertEquals(64, rollHex1.length, "HMAC result should be 32 bytes aka 64 hex chars") },
            { assertTrue(rollHex1.matches(HEX_REGEX), "roll hex should contain only hex characters") },
        )
    }

    @Test
    fun `changing day affects seed hash and ppm`() {
        val todayHash = Fairness.serverSeedHash(fairnessKey, dayUtc)
        val tomorrow = dayUtc.plusDays(1)
        val tomorrowHash = Fairness.serverSeedHash(fairnessKey, tomorrow)

        assertNotEquals(todayHash, tomorrowHash, "serverSeedHash should change for a different day")

        val todaySeed = Fairness.serverSeed(fairnessKey, dayUtc)
        val tomorrowSeed = Fairness.serverSeed(fairnessKey, tomorrow)
        val todayPpm = Fairness.rollPpm(todaySeed, userId, nonce, caseId)
        val tomorrowPpm = Fairness.rollPpm(tomorrowSeed, userId, nonce, caseId)

        assertNotEquals(todayPpm, tomorrowPpm, "ppm should change when the day changes")
    }

    @Test
    fun `receipt contains roll results and client seed`() {
        val serverSeed = Fairness.serverSeed(fairnessKey, dayUtc)
        val serverSeedHash = Fairness.serverSeedHash(fairnessKey, dayUtc)
        val expectedPpm = Fairness.rollPpm(serverSeed, userId, nonce, caseId)
        val expectedRollHex = Fairness.rollHex(serverSeed, userId, nonce, caseId)

        val receipt =
            Fairness.receiptFor(
                serverSeedHash = serverSeedHash,
                serverSeed = serverSeed,
                userId = userId,
                nonce = nonce,
                caseId = caseId,
                dayUtc = dayUtc,
            )

        val expectedClientSeed = "$userId|$nonce|$caseId|v1"

        assertAll(
            { assertEquals(dayUtc, receipt.date) },
            { assertEquals(serverSeedHash, receipt.serverSeedHash) },
            { assertEquals(expectedClientSeed, receipt.clientSeed) },
            { assertEquals(expectedRollHex, receipt.rollHex) },
            { assertEquals(expectedPpm, receipt.ppm) },
        )
    }

    companion object {
        private val HEX_REGEX = Regex("^[0-9a-f]+$")
    }
}
