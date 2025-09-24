package com.example.app.miniapp

import com.example.app.api.miniapp.MiniCaseDto
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class MiniCasesConfigService(
    private val resourceName: String = DEFAULT_RESOURCE_NAME,
    private val inputStreamProvider: (() -> InputStream?)? = null,
    private val yaml: Yaml = Yaml(LoaderOptions().apply { isAllowDuplicateKeys = false }),
) {
    @Volatile
    private var cachedCases: List<MiniCaseDto>? = null

    fun getMiniCases(): List<MiniCaseDto> {
        val cached = cachedCases
        if (cached != null) {
            return cached
        }

        return synchronized(this) {
            cachedCases ?: loadCases().also { loaded -> cachedCases = loaded }
        }
    }

    private fun loadCases(): List<MiniCaseDto> =
        openStream().use { input ->
            val reader = InputStreamReader(input, UTF8)
            val raw: Any? = yaml.load(reader)
            val root = raw as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val entries = root["cases"] as? Iterable<*> ?: emptyList<Any?>()
            entries.mapNotNull(::parseCase)
        }

    private fun openStream(): InputStream {
        inputStreamProvider?.invoke()?.let { stream ->
            return stream
        }

        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        return loader?.getResourceAsStream(resourceName)
            ?: error("Mini app cases configuration '$resourceName' is not available")
    }

    private fun parseCase(node: Any?): MiniCaseDto? =
        when (val map = node as? Map<*, *>) {
            null -> {
                logger.warn("Skipping mini app case entry because it is not a map structure")
                null
            }

            else -> map.toMiniCase()
        }

    private fun Map<*, *>.toMiniCase(): MiniCaseDto? {
        val id = stringValue("id")
        val title = stringValue("title")
        val priceStars = intValue("priceStars", "price_stars")?.takeIf { it > 0 }
        val thumbnail = stringValue("thumbnail")
        val shortDescription = stringValue("shortDescription", "short_description")

        return when {
            id == null -> {
                logger.warn("Skipping mini app case entry because 'id' is missing")
                null
            }

            title == null -> {
                logger.warn("Skipping mini app case '{}' because 'title' is missing", id)
                null
            }

            priceStars == null -> {
                logger.warn("Skipping mini app case '{}' because 'priceStars' is invalid", id)
                null
            }

            thumbnail == null -> {
                logger.warn("Skipping mini app case '{}' because 'thumbnail' is missing", id)
                null
            }

            shortDescription == null -> {
                logger.warn("Skipping mini app case '{}' because 'shortDescription' is missing", id)
                null
            }

            else ->
                MiniCaseDto(
                    id = id,
                    title = title,
                    priceStars = priceStars,
                    thumbnail = thumbnail,
                    shortDescription = shortDescription,
                )
        }
    }

    private fun Map<*, *>.stringValue(vararg keys: String): String? =
        keys
            .asSequence()
            .mapNotNull { key -> this[key]?.toString()?.takeUnless { it.isBlank() } }
            .firstOrNull()

    private fun Map<*, *>.intValue(vararg keys: String): Int? =
        keys
            .asSequence()
            .mapNotNull { key -> parseInt(this[key]) }
            .firstOrNull()

    private fun parseInt(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

    companion object {
        private val UTF8 = StandardCharsets.UTF_8
        private const val DEFAULT_RESOURCE_NAME = "config/cases.yaml"
        private val logger = LoggerFactory.getLogger(MiniCasesConfigService::class.java)
    }
}
