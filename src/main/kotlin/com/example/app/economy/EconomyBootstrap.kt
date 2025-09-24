package com.example.app.economy

import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.economy.economyRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry

fun Application.installEconomyIntegration(meterRegistry: MeterRegistry) {
    val casesRepository = CasesRepository(meterRegistry = meterRegistry)
    casesRepository.reload()

    val adminToken = System.getenv("ADMIN_TOKEN")?.takeUnless { it.isBlank() }

    routing {
        economyRoutes(casesRepository, adminToken)
    }
}
