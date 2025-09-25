package com.example.giftsbot.rng

import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CaseSlotType
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.economy.CasesRoot
import com.example.giftsbot.economy.PrizeItemConfig
import com.example.giftsbot.rng.store.FileRngStore
import com.example.giftsbot.rng.store.InMemoryRngStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Comparator

class RngServiceTest {
    private val fairnessKeyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val fairnessKey: ByteArray = fromHex(fairnessKeyHex)

    private val caseId = "case-1"
    private val userId = 7L
    private val nonce = "nonce-1"

    private val caseConfig =
        CaseConfig(
            id = caseId,
            priceStars = 100,
            rtpExtMin = 0.0,
            rtpExtMax = 1.0,
            jackpotAlpha = 0.1,
            items =
                listOf(
                    PrizeItemConfig(id = "slot-a", type = CaseSlotType.GIFT, probabilityPpm = 200_000),
                    PrizeItemConfig(id = "slot-b", type = CaseSlotType.GIFT, probabilityPpm = 300_000),
                    PrizeItemConfig(id = "slot-c", type = CaseSlotType.INTERNAL, probabilityPpm = 500_000),
                ),
        )

    @Nested
    inner class InMemoryStore : BaseSuite() {
        override fun createStore(clock: Clock): InMemoryRngStore = InMemoryRngStore(clock)
    }

    @Nested
    inner class FileStore : BaseSuite() {
        private val baseDir: Path = Path.of("data", "rng-service-test")

        @BeforeEach
        fun cleanBefore() {
            cleanDirectory(baseDir)
        }

        @AfterEach
        fun cleanAfter() {
            cleanDirectory(baseDir)
        }

        override fun createStore(clock: Clock): InMemoryRngStore = FileRngStore(baseDir, clock)
    }

    abstract inner class BaseSuite {
        protected abstract fun createStore(clock: Clock): InMemoryRngStore

        protected fun ensureTodayCommitIsIdempotent() {
            val context = createContext()

            val first = context.service.ensureTodayCommit()
            val second = context.service.ensureTodayCommit()

            assertEquals(first, second, "ensureTodayCommit should be idempotent for the same day")
            assertEquals(first, context.store.latestCommitted())
        }

        protected fun revealFillsServerSeed() {
            val startDay = LocalDate.of(2024, 6, 1)
            val context = createContext(startDay)

            context.service.ensureTodayCommit()
            context.clock.advanceBy(Duration.ofDays(1))
            context.service.ensureTodayCommit()

            val revealed = context.service.reveal(startDay)

            val expectedSeed = toHex(Fairness.serverSeed(fairnessKey, startDay))
            assertEquals(expectedSeed, revealed.serverSeed)
        }

        protected fun drawIsIdempotentForSameKey() {
            val context = createContext()
            context.service.ensureTodayCommit()

            val first = context.service.draw(caseId, userId, nonce)
            val second = context.service.draw(caseId, userId, nonce)

            assertEquals(first.record, second.record, "draw should reuse existing record for idempotent key")
            assertEquals(first.receipt, second.receipt, "receipt should stay the same for idempotent draw")
        }

        protected fun drawSelectsSlotByPpm() {
            val day = LocalDate.of(2024, 6, 3)
            val context = createContext(day)
            context.service.ensureTodayCommit()

            val serverSeed = Fairness.serverSeed(fairnessKey, day)
            val expectedPpm = Fairness.rollPpm(serverSeed, userId, nonce, caseId)
            val expectedItem = expectedItemId(expectedPpm)

            val result = context.service.draw(caseId, userId, nonce)

            assertEquals(expectedPpm, result.record.ppm, "draw record should store the same ppm as fairness roll")
            assertEquals(expectedPpm, result.receipt.ppm, "receipt should reflect ppm used to resolve prize")
            assertEquals(expectedItem, result.record.resultItemId, "draw should pick slot matching ppm interval")
        }

        protected fun createContext(startDay: LocalDate = LocalDate.of(2024, 6, 2)): ServiceContext {
            val clock = MutableClock(startDay.atStartOfDay(ZoneOffset.UTC).toInstant())
            val store = createStore(clock)
            val registry = SimpleMeterRegistry()
            val repository = CasesRepository(registry, resourcePath = "ignored") { CasesRoot(listOf(caseConfig)) }
            repository.reload()
            val service = RngService(store, store, fairnessKey, repository, registry, clock)
            return ServiceContext(service, store, clock)
        }

        protected fun expectedItemId(ppm: Int): String? {
            var cumulative = 0
            for (item in caseConfig.items) {
                cumulative += item.probabilityPpm
                if (ppm < cumulative) {
                    return item.id
                }
            }
            return null
        }

        @Test
        fun ensureCommitIsIdempotent() {
            ensureTodayCommitIsIdempotent()
        }

        @Test
        fun revealPopulatesServerSeed() {
            revealFillsServerSeed()
        }

        @Test
        fun drawIsIdempotent() {
            drawIsIdempotentForSameKey()
        }

        @Test
        fun drawRespectsPpmDistribution() {
            drawSelectsSlotByPpm()
        }
    }

    data class ServiceContext(
        val service: RngService,
        val store: InMemoryRngStore,
        val clock: MutableClock,
    )

    class MutableClock(
        private var currentInstant: Instant,
        private var currentZone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

        override fun getZone(): ZoneId = currentZone

        override fun instant(): Instant = currentInstant

        fun advanceBy(duration: Duration) {
            currentInstant = currentInstant.plus(duration)
        }
    }

    private fun cleanDirectory(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        Files.walk(path).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
