package com.example.app.rng

import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.rng.RngConfig
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry

fun Application.installRngIntegration(meterRegistry: MeterRegistry) {
    val casesRepository = CasesRepository(meterRegistry = meterRegistry)
    casesRepository.reload()

    val rngService =
        RngConfig.createService(
            meterRegistry = meterRegistry,
            casesRepository = casesRepository,
        )

    val adminToken = System.getenv("ADMIN_TOKEN")?.takeUnless { it.isBlank() }

    routing {
        rngRoutes(
            service = rngService,
            meterRegistry = meterRegistry,
            adminToken = adminToken,
        )
    }
}
