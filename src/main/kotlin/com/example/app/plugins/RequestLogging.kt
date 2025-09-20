package com.example.app.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import org.slf4j.LoggerFactory

fun Application.installRequestLogging() {
    install(CallLogging) {
        logger = requestLogger
        mdc("callId") { call -> call.callId }
        mdc("method") { call -> call.request.httpMethod.value }
        mdc("uri") { call -> call.request.uri }
        mdc("status") { call ->
            val status = call.response.status()
            status?.value?.toString()
        }
        mdc("duration") { call -> call.processingTimeMillis().toString() }
        format { call ->
            val responseStatus = call.response.status()
            val statusValue = responseStatus?.value?.toString() ?: "-"
            val duration = call.processingTimeMillis()
            val requestId = call.callId ?: "-"
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            "$method $uri -> $statusValue (${duration}ms, requestId=$requestId)"
        }
    }
}

private val requestLogger = LoggerFactory.getLogger("com.example.app.RequestLogger")
