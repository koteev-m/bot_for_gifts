package com.example.app.miniapp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.text.Charsets

class MiniCasesConfigServiceTest {
    @Test
    fun `parses valid cases from yaml`() {
        val yaml =
            """
            cases:
              - id: "alpha"
                title: "Alpha Case"
                price_stars: 42
                thumbnail: "https://example.com/alpha.png"
                short_description: "Alpha short description"
                weight: 0.42
              - id: "beta"
                title: "Beta Case"
                priceStars: 17
                thumbnail: "https://example.com/beta.png"
                shortDescription: "Beta short description"
                rewards:
                  - type: "gift"
                    weight: 0.2
            """.trimIndent()

        val service = MiniCasesConfigService(inputStreamProvider = { yaml.byteInputStream(Charsets.UTF_8) })

        val cases = service.getMiniCases()

        assertEquals(2, cases.size)
        val alpha = cases[0]
        assertEquals("alpha", alpha.id)
        assertEquals("Alpha Case", alpha.title)
        assertEquals(42, alpha.priceStars)
        assertEquals("https://example.com/alpha.png", alpha.thumbnail)
        assertEquals("Alpha short description", alpha.shortDescription)

        val beta = cases[1]
        assertEquals("beta", beta.id)
        assertEquals(17, beta.priceStars)
        assertEquals("Beta short description", beta.shortDescription)
    }

    @Test
    fun `skips cases with missing mandatory fields`() {
        val yaml =
            """
            cases:
              - title: "Without id"
                price_stars: 10
                thumbnail: "https://example.com/missing-id.png"
                short_description: "No id provided"
              - id: "gamma"
                title: "Gamma Case"
                price_stars: 50
                thumbnail: "https://example.com/gamma.png"
                short_description: "Gamma description"
              - id: "delta"
                title: "Delta Case"
                price_stars: 0
                thumbnail: "https://example.com/delta.png"
                short_description: "Invalid price"
            """.trimIndent()

        val service = MiniCasesConfigService(inputStreamProvider = { yaml.byteInputStream(Charsets.UTF_8) })

        val cases = service.getMiniCases()

        assertEquals(1, cases.size)
        assertEquals("gamma", cases.single().id)
        assertTrue(cases.single().priceStars > 0)
    }
}
