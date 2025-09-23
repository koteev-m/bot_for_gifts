package com.example.app.testutil

import com.example.app.plugins.installCallIdPlugin
import com.example.app.plugins.installJsonSerialization
import com.example.app.plugins.installMicrometerMetrics
import com.example.app.telegram.UpdateSink
import com.example.app.telegram.adminTelegramWebhookRoutes
import com.example.app.telegram.telegramWebhookRoutes
import com.example.giftsbot.telegram.TelegramApiClient
import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

private val meterRegistryAttribute = AttributeKey<PrometheusMeterRegistry>("test-app-prometheus")

fun Application.testWebhook(
    secret: String,
    sink: UpdateSink,
    path: String = "/tghook",
) {
    require(secret.isNotBlank()) { "secret must not be blank" }
    require(path.isNotBlank()) { "path must not be blank" }
    val registry = ensureTestPlugins()
    routing {
        telegramWebhookRoutes(
            webhookPath = path,
            expectedSecretToken = secret,
            sink = sink,
            meterRegistry = registry,
        )
    }
}

@Suppress("LongParameterList")
fun Application.testAdmin(
    api: TelegramApiClient,
    adminToken: String,
    publicBaseUrl: String,
    webhookPath: String,
    webhookSecret: String,
) {
    require(adminToken.isNotBlank()) { "adminToken must not be blank" }
    require(publicBaseUrl.isNotBlank()) { "publicBaseUrl must not be blank" }
    require(webhookPath.isNotBlank()) { "webhookPath must not be blank" }
    require(webhookSecret.isNotBlank()) { "webhookSecret must not be blank" }
    val registry = ensureTestPlugins()
    routing {
        adminTelegramWebhookRoutes(
            adminToken = adminToken,
            telegramApiClient = api,
            publicBaseUrl = publicBaseUrl,
            webhookPath = webhookPath,
            webhookSecretToken = webhookSecret,
            meterRegistry = registry,
        )
    }
}

private fun Application.ensureTestPlugins(): PrometheusMeterRegistry {
    if (pluginOrNull(ContentNegotiation) == null) {
        installJsonSerialization()
    }
    if (pluginOrNull(CallId) == null) {
        installCallIdPlugin()
    }
    if (attributes.contains(meterRegistryAttribute)) {
        return attributes[meterRegistryAttribute]
    }
    val registry = findExistingPrometheusRegistry() ?: installMicrometerMetrics()
    attributes.put(meterRegistryAttribute, registry)
    return registry
}

private fun Application.findExistingPrometheusRegistry(): PrometheusMeterRegistry? {
    val pluginInstance = pluginOrNull(MicrometerMetrics) ?: return null
    val registry =
        pluginInstance.javaClass.declaredFields
            .firstOrNull { field -> MeterRegistry::class.java.isAssignableFrom(field.type) }
            ?.let { field ->
                field.isAccessible = true
                field.get(pluginInstance)
    }
    return registry as? PrometheusMeterRegistry
}

fun Application.testPrometheusRegistry(): PrometheusMeterRegistry? {
    if (!attributes.contains(meterRegistryAttribute)) {
        return null
    }
    return attributes[meterRegistryAttribute]
}
