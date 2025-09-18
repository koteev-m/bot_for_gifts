package com.example.app

import com.typesafe.config.ConfigFactory
import io.ktor.http.ContentType
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val applicationLogger = LoggerFactory.getLogger("com.example.app.Application")
private val requestLogger = LoggerFactory.getLogger("com.example.app.RequestLogger")
private val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

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
    configureMonitoring()
    configureStatusPages()
    configureRouting()
}

private fun Application.configureHttp() {
    install(DefaultHeaders) {
        header(name = "Server", value = "gifts-bot")
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

private fun Application.configureMonitoring() {
    install(CallLogging) {
        logger = requestLogger
        format { call ->
            buildString {
                append(call.request.httpMethod.value)
                append(' ')
                append(call.request.uri)
                append(" -> ")
                append(
                    call.response
                        .status()
                        ?.value
                        ?.toString() ?: "-",
                )
                append(" (requestId=")
                append(call.callId ?: "-")
                append(')')
            }
        }
    }

    install(MicrometerMetrics) {
        registry = prometheusRegistry
        meterBinders =
            listOf(
                ClassLoaderMetrics(),
                JvmMemoryMetrics(),
                JvmGcMetrics(),
                JvmThreadMetrics(),
                ProcessorMetrics(),
            )
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
                errorResponse(HttpStatusCode.BadRequest, cause.message, call.callId),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                errorResponse(status, message = "Resource not found", callId = call.callId),
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
                errorResponse(
                    HttpStatusCode.InternalServerError,
                    message = "Internal server error",
                    callId = call.callId,
                ),
            )
        }
    }
}

private fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respondText("OK", contentType = ContentType.Text.Plain)
        }

        get("/metrics") {
            call.respondText(
                text = prometheusRegistry.scrape(),
                contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
        }

        get("/version") {
            call.respond(this@configureRouting.versionInfo())
        }
    }
}

private fun errorResponse(
    status: HttpStatusCode,
    message: String? = null,
    callId: String?,
): ErrorResponse =
    ErrorResponse(
        status = status.value,
        error = status.description,
        message = message,
        requestId = callId,
        timestamp = OffsetDateTime.now(Clock.systemUTC()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

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
private data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String?,
    val requestId: String?,
    val timestamp: String,
)

@Serializable
private data class VersionResponse(
    val app: String,
    val version: String,
    val git: String?,
    val buildTime: String?,
)
