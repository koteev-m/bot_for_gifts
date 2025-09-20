package com.example.app.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentRegexRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.TrailingSlashRouteSelector
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

fun Application.installMicrometerMetrics(): PrometheusMeterRegistry {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val appName =
        System.getProperty("app")?.takeUnless { it.isBlank() }
            ?: System.getProperty("app.name")?.takeUnless { it.isBlank() }
            ?: DEFAULT_APP_NAME
    val appVersion =
        System.getProperty("version")?.takeUnless { it.isBlank() }
            ?: System.getProperty("app.version")?.takeUnless { it.isBlank() }
            ?: DEFAULT_APP_VERSION
    val metricTags = Tags.of("app", appName, "version", appVersion)

    registry.counter("app_startups_total", metricTags).increment()
    Gauge
        .builder("app_build_info") { 1.0 }
        .description("Build information for the running application")
        .tags(metricTags)
        .register(registry)

    install(MicrometerMetrics) {
        this.registry = registry
        meterBinders =
            listOf(
                ClassLoaderMetrics(),
                JvmMemoryMetrics(),
                JvmGcMetrics(),
                JvmThreadMetrics(),
                ProcessorMetrics(),
            )
        distinctNotRegisteredRoutes = false
        transformRoute { node -> buildRoutePath(node) }
    }

    return registry
}

private const val DEFAULT_APP_NAME = "gifts-bot"
private const val DEFAULT_APP_VERSION = "dev"

private fun buildRoutePath(node: RoutingNode): String {
    val segments =
        generateSequence(node) { current -> current.parent as? RoutingNode }
            .mapNotNull { current -> selectorSegment(current) }
            .toList()
            .asReversed()

    val pathSegments = segments.filter { it.isNotEmpty() }
    return if (pathSegments.isEmpty()) {
        "/"
    } else {
        "/" + pathSegments.joinToString(separator = "/")
    }
}

private fun selectorSegment(node: RoutingNode): String? =
    when (val selector = node.selector) {
        is RootRouteSelector -> null
        is PathSegmentConstantRouteSelector -> selector.value
        is PathSegmentParameterRouteSelector -> "{${selector.name}}"
        is PathSegmentOptionalParameterRouteSelector -> "{${selector.name}?}"
        is PathSegmentWildcardRouteSelector -> "*"
        is PathSegmentTailcardRouteSelector -> "**"
        is PathSegmentRegexRouteSelector -> selector.toString()
        is TrailingSlashRouteSelector -> ""
        else -> selector.toString().takeUnless { it.isBlank() }
    }
