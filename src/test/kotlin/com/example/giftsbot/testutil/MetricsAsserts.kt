package com.example.giftsbot.testutil

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.junit.jupiter.api.Assertions.assertTrue

suspend fun getMetricsText(client: HttpClient): String = client.get("/metrics").bodyAsText()

fun assertMetricContains(
    text: String,
    name: String,
) {
    assertTrue(text.contains(name), "Expected metrics to contain '$name'")
}

fun assertMetricContainsAny(
    text: String,
    names: List<String>,
) {
    assertTrue(names.any(text::contains), "Expected metrics to contain any of ${names.joinToString()}")
}
