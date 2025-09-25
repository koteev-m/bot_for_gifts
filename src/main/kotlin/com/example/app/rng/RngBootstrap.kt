package com.example.app.rng

import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.rng.RngConfig
import com.example.giftsbot.rng.SystemEnvReader
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Clock

private val logger = LoggerFactory.getLogger("RngBootstrap")

fun Application.installRngIntegration(meterRegistry: MeterRegistry) {
    val env = SystemEnvReader
    val clock = Clock.systemUTC()
    val storage = RngConfig.resolveStorage(env)
    logger.info("Initializing RNG storage={}", storage)

    val store = RngConfig.createRngStoreFromEnv(env = env, clock = clock)

    val casesRepository = CasesRepository(meterRegistry = meterRegistry)
    casesRepository.reload()

    val rngService =
        RngConfig.createService(
            meterRegistry = meterRegistry,
            casesRepository = casesRepository,
            env = env,
            clock = clock,
            store = store,
        )

    val adminToken = env.get("ADMIN_TOKEN")

    routing {
        rngRoutes(
            service = rngService,
            meterRegistry = meterRegistry,
            adminToken = adminToken,
        )
    }
}
