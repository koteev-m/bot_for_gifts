package com.example.bootstrap

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StatusPayload(
    val status: String,
)

fun main() {
    val json = Json { prettyPrint = false }
    val payload = StatusPayload(status = "OK")
    val serialized = json.encodeToString(payload)
    val decoded = json.decodeFromString<StatusPayload>(serialized)
    println(decoded.status)
}
