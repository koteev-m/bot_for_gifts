package com.example.app.util

import io.ktor.server.application.Application

fun Application.configValue(
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

    val applicationConfig = environment.config
    val configValues =
        configKeys
            .asSequence()
            .mapNotNull { key ->
                val configEntry = applicationConfig.propertyOrNull(key)
                val value = configEntry?.getString()
                value?.takeUnless { it.isBlank() }
            }
    val configValue = configValues.firstOrNull()

    return propertyValue ?: environmentValue ?: configValue
}
