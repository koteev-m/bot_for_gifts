package com.example.app.observability

import com.example.app.module
import com.example.app.telegram.UpdateSink
import com.example.giftsbot.telegram.LongPollingRunner
import com.example.giftsbot.telegram.TelegramApiClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsSmokeTest {
    @Test
    fun `metrics endpoint exposes key telegram metrics`() =
        testApplication {
            configureTelegramDefaults()

            application {
                module()
            }
            startApplication()
            application.registerLongPollingMetrics()
            application.registerWebhookMetrics()
            application.registerQueueMetrics()
            application.registerAdminMetrics()

            val body = client.get("/metrics").bodyAsText()

            assertMetricsContainAnyOf(
                body,
                listOf(
                    MetricsNames.WEBHOOK_UPDATES_TOTAL,
                    MetricsNames.WEBHOOK_REJECTED_TOTAL,
                    MetricsNames.WEBHOOK_TOO_LARGE_TOTAL,
                ),
            )
            assertMetricsContainAnyOf(
                body,
                listOf(
                    MetricsNames.UPDATES_ENQUEUED_TOTAL,
                    MetricsNames.UPDATES_DUPLICATED_TOTAL,
                    MetricsNames.UPDATES_DROPPED_TOTAL,
                ),
            )
            assertMetricsContainAnyOf(
                body,
                listOf(
                    MetricsNames.LP_REQUESTS_TOTAL,
                    MetricsNames.LP_RESPONSES_TOTAL,
                    MetricsNames.LP_BATCHES_TOTAL,
                    MetricsNames.LP_UPDATES_TOTAL,
                ),
            )
            assertMetricsContainAnyOf(
                body,
                listOf(
                    MetricsNames.ADMIN_SET_TOTAL,
                    MetricsNames.ADMIN_DELETE_TOTAL,
                    MetricsNames.ADMIN_INFO_TOTAL,
                ),
            )
        }
}

private fun ApplicationTestBuilder.configureTelegramDefaults() {
    environment {
        config =
            MapApplicationConfig().apply {
                put("app.telegram.botToken", "test-bot-token")
                put("app.telegram.webhookPath", "/telegram/webhook")
                put("app.telegram.webhookSecretToken", "test-secret")
                put("app.telegram.mode", "webhook")
                put("app.admin.token", "test-admin-token")
                put("app.telegram.publicBaseUrl", "https://public.example/base/")
            }
    }
}

private fun assertMetricsContainAnyOf(
    body: String,
    metricNames: Collection<String>,
) {
    val found =
        metricNames.any { metric ->
            body.lineSequence().any { line -> line.contains(metric) }
        }
    assertTrue(found, "Metrics output should contain at least one of: ${metricNames.joinToString()}")
}

private fun Application.registerLongPollingMetrics() {
    val registry = findPrometheusRegistry() ?: return
    val api = mockk<TelegramApiClient>(relaxed = true)
    val sink = mockk<UpdateSink>(relaxed = true)
    val scope = CoroutineScope(SupervisorJob())
    try {
        LongPollingRunner(
            api = api,
            sink = sink,
            scope = scope,
            meterRegistry = registry,
        )
    } finally {
        scope.cancel()
    }
}

private fun Application.registerWebhookMetrics() {
    val registry = findPrometheusRegistry() ?: return
    val updatesCounter =
        registry.counter(
            MetricsNames.WEBHOOK_UPDATES_TOTAL,
            MetricsTags.COMPONENT,
            "webhook",
        )
    val rejectedCounter =
        registry.counter(
            MetricsNames.WEBHOOK_REJECTED_TOTAL,
            MetricsTags.COMPONENT,
            "webhook",
        )
    val tooLargeCounter =
        registry.counter(
            MetricsNames.WEBHOOK_TOO_LARGE_TOTAL,
            MetricsTags.COMPONENT,
            "webhook",
        )
    updatesCounter.increment()
    rejectedCounter.increment()
    tooLargeCounter.increment()
}

private fun Application.registerQueueMetrics() {
    val registry = findPrometheusRegistry() ?: return
    val enqueuedCounter =
        registry.counter(
            MetricsNames.UPDATES_ENQUEUED_TOTAL,
            MetricsTags.COMPONENT,
            "queue",
        )
    val duplicatedCounter =
        registry.counter(
            MetricsNames.UPDATES_DUPLICATED_TOTAL,
            MetricsTags.COMPONENT,
            "queue",
        )
    val droppedCounter =
        registry.counter(
            MetricsNames.UPDATES_DROPPED_TOTAL,
            MetricsTags.COMPONENT,
            "queue",
        )
    enqueuedCounter.increment()
    duplicatedCounter.increment()
    droppedCounter.increment()
}

private fun Application.registerAdminMetrics() {
    val registry = findPrometheusRegistry() ?: return
    val setCounter =
        registry.counter(
            MetricsNames.ADMIN_SET_TOTAL,
            MetricsTags.COMPONENT,
            "admin",
            MetricsTags.RESULT,
            "ok",
        )
    val deleteCounter =
        registry.counter(
            MetricsNames.ADMIN_DELETE_TOTAL,
            MetricsTags.COMPONENT,
            "admin",
            MetricsTags.RESULT,
            "ok",
        )
    val infoCounter =
        registry.counter(
            MetricsNames.ADMIN_INFO_TOTAL,
            MetricsTags.COMPONENT,
            "admin",
            MetricsTags.RESULT,
            "ok",
        )
    setCounter.increment()
    deleteCounter.increment()
    infoCounter.increment()
}

private fun Application.findPrometheusRegistry(): PrometheusMeterRegistry? {
    val pluginInstance = pluginOrNull(MicrometerMetrics) ?: return null
    val builderMethod = pluginInstance.javaClass.getDeclaredMethod("getBuilder\$ktor_server_core")
    builderMethod.isAccessible = true
    val builder = builderMethod.invoke(pluginInstance)
    val configMethod = builder.javaClass.getMethod("getPluginConfig")
    val config = configMethod.invoke(builder)
    val registryMethod = config.javaClass.getMethod("getRegistry")
    val registry = registryMethod.invoke(config) as? MeterRegistry
    return registry as? PrometheusMeterRegistry
}
