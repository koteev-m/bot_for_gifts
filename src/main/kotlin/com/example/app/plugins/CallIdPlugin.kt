package com.example.app.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.response.header
import kotlin.random.Random

fun Application.installCallIdPlugin() {
    install(CallId) {
        retrieve { call ->
            call.request.headers[REQUEST_ID_HEADER]?.takeUnless { it.isBlank() }
        }
        generate { generateRequestId() }
        verify { callId ->
            callId.isNotBlank() && callId.length in MIN_REQUEST_ID_LENGTH..MAX_REQUEST_ID_LENGTH
        }
        reply { call, callId ->
            call.response.header(name = REQUEST_ID_HEADER, value = callId)
        }
    }
}

private fun generateRequestId(): String {
    val alphabet = REQUEST_ID_ALPHABET
    return buildString(REQUEST_ID_LENGTH) {
        repeat(REQUEST_ID_LENGTH) {
            append(alphabet[Random.nextInt(alphabet.length)])
        }
    }
}

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val REQUEST_ID_LENGTH = 12
private const val MIN_REQUEST_ID_LENGTH = 8
private const val MAX_REQUEST_ID_LENGTH = 64
private const val REQUEST_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
