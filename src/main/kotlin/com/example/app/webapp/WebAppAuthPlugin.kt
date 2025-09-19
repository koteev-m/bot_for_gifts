package com.example.app.webapp

import com.example.app.api.errorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

private val webAppAuthLogger = LoggerFactory.getLogger("com.example.app.webapp.WebAppAuth")

class WebAppAuthConfig {
    lateinit var botToken: String
    var clock: Clock = Clock.systemUTC()
    var json: Json = Json { ignoreUnknownKeys = true }
}

object WebAppAuth {
    val ContextKey: AttributeKey<WebAppAuthContext> = AttributeKey("TelegramWebAppContext")
    val UserIdKey: AttributeKey<Long> = AttributeKey("TelegramWebAppUserId")
    val AuthDateKey: AttributeKey<Instant> = AttributeKey("TelegramWebAppAuthDate")
    val ChatTypeKey: AttributeKey<String> = AttributeKey("TelegramWebAppChatType")
}

data class WebAppAuthContext(
    val userId: Long,
    val chatType: String?,
    val authDate: Instant,
)

val WebAppAuthPlugin =
    createRouteScopedPlugin(name = "WebAppAuthPlugin", createConfiguration = ::WebAppAuthConfig) {
        require(pluginConfig.botToken.isNotBlank()) { "WebAppAuthPlugin requires non-empty botToken" }
        val botToken = pluginConfig.botToken
        val json = pluginConfig.json
        val clock = pluginConfig.clock

        onCall { call ->
            val rawInitData = call.extractInitData()
            if (rawInitData.isNullOrBlank()) {
                logVerification(call, userId = null, authDate = null, success = false, reason = "missing")
                call.respondForbidden(clock, "Mini app init data is required")
                return@onCall
            }

            val parsed = InitDataVerifier.parse(rawInitData)
            val hash = parsed.hash
            val userId = parsed.parameters["user"]?.firstOrNull()?.let { parseUserId(json, it) }
            val authDateSeconds = parsed.parameters["auth_date"]?.firstOrNull()?.toLongOrNull()
            val chatType = parsed.parameters["chat_type"]?.firstOrNull()?.takeUnless { it.isBlank() }

            val signatureIsValid =
                hash != null && InitDataVerifier.verify(parsed.parameters, hash, botToken)
            if (!signatureIsValid) {
                logVerification(call, userId, authDateSeconds, success = false, reason = "signature_mismatch")
                call.respondForbidden(clock, "Invalid init data signature")
                return@onCall
            }

            if (userId == null || authDateSeconds == null) {
                logVerification(call, userId, authDateSeconds, success = false, reason = "missing_fields")
                call.respondForbidden(clock, "Required init data fields are missing")
                return@onCall
            }

            val authDate = Instant.ofEpochSecond(authDateSeconds)
            val context =
                WebAppAuthContext(
                    userId = userId,
                    chatType = chatType,
                    authDate = authDate,
                )
            call.attributes.put(WebAppAuth.ContextKey, context)
            call.attributes.put(WebAppAuth.UserIdKey, context.userId)
            call.attributes.put(WebAppAuth.AuthDateKey, context.authDate)
            context.chatType?.let { call.attributes.put(WebAppAuth.ChatTypeKey, it) }

            logVerification(call, context.userId, authDateSeconds, success = true)
        }
    }

private val initDataKeys = listOf("init_data", "initData", "init-data")
private val initDataHeaders =
    listOf(
        "X-Telegram-Init-Data",
        "X-Telegram-InitData",
        "X-Telegram-WebApp-Init-Data",
        "X-Telegram-Web-App-Init-Data",
    )

private suspend fun ApplicationCall.extractInitData(): String? {
    val queryValue =
        initDataKeys
            .asSequence()
            .mapNotNull { key -> request.queryParameters[key]?.takeUnless { it.isBlank() } }
            .firstOrNull()

    val headerValue =
        initDataHeaders
            .asSequence()
            .mapNotNull { header -> request.headers[header]?.takeUnless { it.isBlank() } }
            .firstOrNull()

    return queryValue ?: headerValue ?: extractInitDataFromBody()
}

private suspend fun ApplicationCall.extractInitDataFromBody(): String? {
    val contentType = request.contentType()
    return when {
        contentType.match(ContentType.Application.FormUrlEncoded) ->
            runCatching { receiveParameters() }.getOrNull()?.let { params ->
                initDataKeys
                    .asSequence()
                    .mapNotNull { key -> params[key]?.takeUnless { it.isBlank() } }
                    .firstOrNull()
            }

        contentType.match(ContentType.Application.Json) ->
            runCatching { receiveText() }.getOrNull()?.let(::extractInitDataFromJson)

        contentType.match(ContentType.Text.Plain) ->
            runCatching { receiveText() }.getOrNull()?.takeUnless { it.isBlank() }

        request.httpMethod == HttpMethod.Get -> null

        else -> runCatching { receiveText() }.getOrNull()?.takeUnless { it.isBlank() }
    }
}

private fun extractInitDataFromJson(payload: String): String? {
    if (payload.isBlank()) {
        return null
    }

    val jsonObject =
        runCatching { Json.parseToJsonElement(payload).jsonObject }
            .getOrNull()

    return jsonObject?.let { element ->
        initDataKeys
            .asSequence()
            .mapNotNull { key -> element[key]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() } }
            .firstOrNull()
    }
}

private fun parseUserId(
    json: Json,
    rawUser: String,
): Long? = runCatching { json.decodeFromString<TelegramUserPayload>(rawUser).id }.getOrNull()

private suspend fun ApplicationCall.respondForbidden(
    clock: Clock,
    reason: String,
) = respond(
    HttpStatusCode.Forbidden,
    errorResponse(
        status = HttpStatusCode.Forbidden,
        reason = reason,
        callId = callId,
        clock = clock,
    ),
)

private fun logVerification(
    call: ApplicationCall,
    userId: Long?,
    authDate: Long?,
    success: Boolean,
    reason: String? = null,
) {
    val requestId = call.callId ?: "-"
    val userForLog = userId?.toString() ?: "-"
    val authDateForLog = authDate?.toString() ?: "-"

    if (success) {
        webAppAuthLogger.info(
            "Mini app init data verified (userId={}, auth_date={}, requestId={})",
            userForLog,
            authDateForLog,
            requestId,
        )
    } else {
        webAppAuthLogger.warn(
            "Mini app init data rejected (userId={}, auth_date={}, requestId={}, reason={})",
            userForLog,
            authDateForLog,
            requestId,
            reason ?: "unspecified",
        )
    }
}

@kotlinx.serialization.Serializable
private data class TelegramUserPayload(
    val id: Long,
)
