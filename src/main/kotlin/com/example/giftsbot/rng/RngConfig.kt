package com.example.giftsbot.rng

import com.example.giftsbot.data.createHikariDataSource
import com.example.giftsbot.data.createJdbi
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.rng.store.DbRngStore
import com.example.giftsbot.rng.store.FileRngStore
import com.example.giftsbot.rng.store.InMemoryRngStore
import io.micrometer.core.instrument.MeterRegistry
import java.nio.file.Paths
import java.time.Clock
import java.util.Locale

interface RngStore :
    RngCommitStore,
    RngDrawStore

fun interface EnvReader {
    fun get(key: String): String?
}

object SystemEnvReader : EnvReader {
    override fun get(key: String): String? = System.getenv(key)?.trim()?.takeUnless { it.isEmpty() }
}

object RngConfig {
    fun createService(
        meterRegistry: MeterRegistry,
        casesRepository: CasesRepository,
        env: EnvReader = SystemEnvReader,
        clock: Clock = Clock.systemUTC(),
        store: RngStore = createRngStoreFromEnv(env, clock),
    ): RngService {
        val fairnessKey = loadFairnessKey(env)
        return RngService(
            commitStore = store,
            drawStore = store,
            fairnessKey = fairnessKey,
            casesRepository = casesRepository,
            meterRegistry = meterRegistry,
            clock = clock,
        )
    }

    fun createRngStoreFromEnv(
        env: EnvReader,
        clock: Clock = Clock.systemUTC(),
    ): RngStore =
        when (val storage = resolveStorage(env)) {
            MEMORY_STORAGE -> wrapStore(InMemoryRngStore(clock))
            FILE_STORAGE -> {
                val dir = env.value(RNG_DATA_DIR_ENV) ?: DEFAULT_DATA_DIR
                wrapStore(FileRngStore(Paths.get(dir), clock))
            }
            DB_STORAGE -> createDbStore(env)
            else -> error("Unsupported RNG_STORAGE value '$storage'")
        }

    fun resolveStorage(env: EnvReader): String = env.value(RNG_STORAGE_ENV)?.lowercase(Locale.ROOT) ?: MEMORY_STORAGE

    private fun loadFairnessKey(env: EnvReader): ByteArray {
        val rawKey = env.value(FAIRNESS_KEY_ENV)
        require(rawKey != null) { "FAIRNESS_KEY env variable is required" }
        return decodeFairnessKey(rawKey)
    }

    private fun createDbStore(env: EnvReader): RngStore {
        val url = env.require(DATABASE_URL_ENV)
        val user = env.require(DATABASE_USER_ENV)
        val password = env.require(DATABASE_PASSWORD_ENV)
        val dataSource = createHikariDataSource(url, user, password)
        val jdbi = createJdbi(dataSource)
        return DbRngStore(jdbi)
    }

    private fun wrapStore(store: InMemoryRngStore): RngStore =
        object : RngStore, RngCommitStore by store, RngDrawStore by store {}

    private fun EnvReader.value(key: String): String? = get(key)?.trim()?.takeUnless { it.isEmpty() }

    private fun EnvReader.require(key: String): String = value(key) ?: error("$key env variable is required")

    private const val FAIRNESS_KEY_ENV = "FAIRNESS_KEY"
    private const val RNG_STORAGE_ENV = "RNG_STORAGE"
    private const val RNG_DATA_DIR_ENV = "RNG_DATA_DIR"
    const val MEMORY_STORAGE = "memory"
    const val FILE_STORAGE = "file"
    const val DB_STORAGE = "db"
    private const val DATABASE_URL_ENV = "DATABASE_URL"
    private const val DATABASE_USER_ENV = "DATABASE_USER"
    private const val DATABASE_PASSWORD_ENV = "DATABASE_PASSWORD"
    private const val DEFAULT_DATA_DIR = "./data"
}
