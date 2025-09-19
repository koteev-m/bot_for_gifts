package com.example.app

import com.typesafe.config.ConfigFactory
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.path
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

private val applicationLogger = LoggerFactory.getLogger("com.example.app.Application")
private val requestLogger = LoggerFactory.getLogger("com.example.app.RequestLogger")
internal val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

fun main() {
    val environment =
        applicationEnvironment {
            log = applicationLogger
            config = HoconApplicationConfig(ConfigFactory.load())
        }

    embeddedServer(Netty, environment = environment) {
        module()
    }.start(wait = true)
}

@Suppress("unused")
fun Application.module() {
    configureHttp()
    configureCallIdPlugin()
    val buildInfo = versionInfo()
    configureMonitoring(buildInfo)
    configureStatusPages()
    configureRouting(buildInfo)
}

private fun Application.configureHttp() {
    install(DefaultHeaders) {
        header(name = "Server", value = "gifts-bot")
        header(name = "X-Content-Type-Options", value = "nosniff")
        header(name = "X-Frame-Options", value = "DENY")
        header(name = "Referrer-Policy", value = "no-referrer")
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            },
        )
    }

    install(ConditionalHeaders)
    install(DoubleReceive)
}

private fun Application.configureCallIdPlugin() {
    install(CallId) {
        retrieve { call ->
            call.request.headers["X-Request-Id"]?.takeUnless { it.isBlank() }
        }
        generate { UUID.randomUUID().toString() }
        verify { callId -> callId.isNotBlank() }
        reply { call, callId ->
            call.response.headers.append("X-Request-Id", callId, safeOnly = false)
        }
    }
}

private fun Application.configureMonitoring(buildInfo: VersionResponse) {
    val metricTags = Tags.of("app", buildInfo.app, "version", buildInfo.version)
    prometheusRegistry.counter("app_startups_total", metricTags).increment()
    Gauge
        .builder("app_build_info") { 1.0 }
        .description("Build information for the running application")
        .tags(metricTags)
        .register(prometheusRegistry)

    install(CallLogging) {
        logger = requestLogger
        mdc("callId") { call -> call.callId }
        mdc("method") { call -> call.request.httpMethod.value }
        mdc("uri") { call -> call.request.uri }
        mdc("status") { call ->
            call.response.status()?.let { status -> status.value.toString() }
        }
        mdc("duration") { call -> call.processingTimeMillis().toString() }
        format { call ->
            val status =
                call.response
                    .status()
                    ?.value
                    ?.toString()
                    ?: "-"
            val duration = call.processingTimeMillis()
            val requestId = call.callId ?: "-"
            "${call.request.httpMethod.value} ${call.request.uri} -> $status (${duration}ms, requestId=$requestId)"
        }
    }

    install(MicrometerMetrics) {
        registry = prometheusRegistry
        distinctNotRegisteredRoutes = false
        meterBinders =
            listOf(
                ClassLoaderMetrics(),
                JvmMemoryMetrics(),
                JvmGcMetrics(),
                JvmThreadMetrics(),
                ProcessorMetrics(),
            )
        transformRoute { node ->
            val routePath = node.path
            if (routePath.isBlank()) "/" else routePath
        }
    }
}

private fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            applicationLogger.error(
                "Bad request for {} {} (requestId={})",
                call.request.httpMethod.value,
                call.request.uri,
                call.callId ?: "-",
                cause,
            )
            call.respond(
                HttpStatusCode.BadRequest,
                com.example.app.api.errorResponse(
                    status = HttpStatusCode.BadRequest,
                    reason =
                        cause.message?.takeUnless { it.isBlank() }
                            ?: HttpStatusCode.BadRequest.description,
                    callId = call.callId,
                ),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                com.example.app.api.errorResponse(
                    status = status,
                    reason = "Resource not found",
                    callId = call.callId,
                ),
            )
        }
        exception<Throwable> { call, cause ->
            applicationLogger.error(
                "Unhandled exception for {} {} (requestId={})",
                call.request.httpMethod.value,
                call.request.uri,
                call.callId ?: "-",
                cause,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                com.example.app.api.errorResponse(
                    status = HttpStatusCode.InternalServerError,
                    reason = "Internal server error",
                    callId = call.callId,
                ),
            )
        }
    }
}

private fun Application.configureRouting(versionResponse: VersionResponse) {
    val config = environment.config
    val healthPath = config.propertyOrNull("app.healthPath")?.getString()?.takeUnless { it.isBlank() } ?: "/health"
    val metricsPath = config.propertyOrNull("app.metricsPath")?.getString()?.takeUnless { it.isBlank() } ?: "/metrics"

    val configuredMiniAppPath =
        configValue(
            propertyKeys = listOf("miniapp.dist"),
            envKeys = listOf("MINIAPP_DIST"),
            configKeys = listOf("app.miniapp.dist"),
        )?.takeUnless { it.isBlank() }

    val miniAppRoot =
        sequenceOf(configuredMiniAppPath, "miniapp/dist")
            .filterNotNull()
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?.absoluteFile
    val miniAppIndex = miniAppRoot?.resolve("index.html")?.takeIf { it.isFile }

    val botToken =
        configValue(
            propertyKeys = listOf("bot.token", "telegram.bot.token"),
            envKeys = listOf("BOT_TOKEN", "TELEGRAM_BOT_TOKEN"),
            configKeys = listOf("app.telegram.botToken", "telegram.botToken"),
        )?.takeUnless { it.isBlank() }

    if (miniAppRoot == null || miniAppIndex == null) {
        applicationLogger.warn(
            "Mini app bundle is not available. Build the frontend via `npm ci && npm run build`.",
        )
    }

    if (botToken == null) {
        applicationLogger.warn(
            "Telegram bot token is not configured; Mini App API authentication is disabled.",
        )
    }

    routing {
        registerOperationalRoutes(healthPath, metricsPath, versionResponse)
        registerMiniAppRoutes(miniAppRoot, miniAppIndex)
        registerMiniAppApiRoutes(botToken)
    }
}

private fun Application.versionInfo(): VersionResponse =
    VersionResponse(
        app =
            configValue(
                propertyKeys = listOf("app", "app.name"),
                envKeys = listOf("APP", "APP_NAME"),
                configKeys = listOf("ktor.application.app"),
            ) ?: "gifts-bot",
        version =
            configValue(
                propertyKeys = listOf("version", "app.version"),
                envKeys = listOf("VERSION", "APP_VERSION"),
                configKeys = listOf("ktor.application.version"),
            ) ?: "dev",
        git =
            configValue(
                propertyKeys = listOf("git", "git.commit"),
                envKeys = listOf("GIT", "GIT_COMMIT", "GIT_SHA"),
                configKeys = listOf("ktor.application.git", "ktor.deployment.git"),
            ),
        buildTime =
            configValue(
                propertyKeys = listOf("buildTime", "build.time"),
                envKeys = listOf("BUILD_TIME"),
                configKeys = listOf("ktor.application.buildTime", "ktor.deployment.buildTime"),
            ),
    )

private fun Application.configValue(
    propertyKeys: List<String>,
    envKeys: List<String>,
    configKeys: List<String> = emptyList(),
): String? {
    val propertyValue =
        propertyKeys
            .asSequence()
            .mapNotNull { key -> System.getProperty(key)?.takeUnless { it.isBlank() } }
            .firstOrNull()

    val environmentValue =
        envKeys
            .asSequence()
            .mapNotNull { key -> System.getenv(key)?.takeUnless { it.isBlank() } }
            .firstOrNull()

    val configValue =
        configKeys
            .asSequence()
            .mapNotNull { key ->
                environment.config
                    .propertyOrNull(key)
                    ?.getString()
                    ?.takeUnless { it.isBlank() }
            }.firstOrNull()

    return propertyValue ?: environmentValue ?: configValue
}

@Serializable
internal data class VersionResponse(
    val app: String,
    val version: String,
    val git: String?,
    val buildTime: String?,
)
