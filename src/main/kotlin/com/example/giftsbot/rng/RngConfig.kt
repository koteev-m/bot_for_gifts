package com.example.giftsbot.rng

import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.rng.store.FileRngStore
import com.example.giftsbot.rng.store.InMemoryRngStore
import io.micrometer.core.instrument.MeterRegistry
import java.nio.file.Paths
import java.time.Clock
import java.util.Locale

object RngConfig {
    fun createService(
        meterRegistry: MeterRegistry,
        casesRepository: CasesRepository,
        clock: Clock = Clock.systemUTC(),
    ): RngService {
        val fairnessKey = loadFairnessKey()
        val store = createStore(clock)
        return RngService(
            commitStore = store,
            drawStore = store,
            fairnessKey = fairnessKey,
            casesRepository = casesRepository,
            meterRegistry = meterRegistry,
            clock = clock,
        )
    }

    private fun loadFairnessKey(): ByteArray {
        val rawKey = System.getenv(FAIRNESS_KEY_ENV)?.takeUnless { it.isBlank() }
        require(rawKey != null) { "FAIRNESS_KEY env variable is required" }
        return decodeFairnessKey(rawKey)
    }

    private fun createStore(clock: Clock): InMemoryRngStore {
        val storageEnv = System.getenv(RNG_STORAGE_ENV)?.takeUnless { it.isBlank() }
        val storage = storageEnv?.lowercase(Locale.ROOT) ?: MEMORY_STORAGE
        return when (storage) {
            MEMORY_STORAGE -> InMemoryRngStore(clock)
            FILE_STORAGE -> {
                val dir = System.getenv(RNG_DATA_DIR_ENV)?.takeUnless { it.isBlank() } ?: DEFAULT_DATA_DIR
                FileRngStore(Paths.get(dir), clock)
            }
            else -> error("Unsupported RNG_STORAGE value '$storage'")
        }
    }

    private const val FAIRNESS_KEY_ENV = "FAIRNESS_KEY"
    private const val RNG_STORAGE_ENV = "RNG_STORAGE"
    private const val RNG_DATA_DIR_ENV = "RNG_DATA_DIR"
    private const val MEMORY_STORAGE = "memory"
    private const val FILE_STORAGE = "file"
    private const val DEFAULT_DATA_DIR = "./data"
}
