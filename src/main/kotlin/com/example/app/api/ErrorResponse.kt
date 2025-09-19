package com.example.app.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String? = null,
    val requestId: String? = null,
    val timestamp: String,
)

fun errorResponse(
    status: HttpStatusCode,
    reason: String,
    message: String? = null,
    callId: String?,
    clock: Clock = Clock.systemUTC(),
): ErrorResponse =
    ErrorResponse(
        status = status.value,
        error = reason,
        message = message,
        requestId = callId,
        timestamp = OffsetDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )
