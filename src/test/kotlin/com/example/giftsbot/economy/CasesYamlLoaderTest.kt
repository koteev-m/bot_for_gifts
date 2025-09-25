package com.example.giftsbot.economy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CasesYamlLoaderTest {
    @Test
    fun `loadFromResources parses production cases`() {
        val root = CasesYamlLoader.loadFromResources()

        assertEquals(6, root.cases.size)
        assertEquals(
            listOf("micro", "bronze", "silver", "gold", "diamond", "mythic"),
            root.cases.map { it.id },
        )

        val micro = root.cases.first { it.id == "micro" }
        assertEquals(29, micro.priceStars)
        assertEquals(0.35, micro.rtpExtMin)
        assertEquals(0.42, micro.rtpExtMax)
        assertEquals(0.012, micro.jackpotAlpha)
        assertEquals(5, micro.items.size)

        val premiumItem = micro.items.first()
        assertEquals("micro-premium-3m", premiumItem.id)
        assertEquals(CaseSlotType.PREMIUM_3M, premiumItem.type)
        assertEquals(99, premiumItem.starCost)
        assertEquals(8_000, premiumItem.probabilityPpm)

        val internalItem = micro.items.last()
        assertEquals("micro-internal-default", internalItem.id)
        assertEquals(CaseSlotType.INTERNAL, internalItem.type)
        assertEquals(null, internalItem.starCost)
        assertEquals(652_000, internalItem.probabilityPpm)
    }

    @Test
    fun `computePreview returns expected external metrics`() {
        val case = CasesYamlLoader.loadFromResources().cases.first { it.id == "micro" }

        val preview = CasesYamlLoader.computePreview(case)

        val expectedSumPpm = case.items.sumOf { it.probabilityPpm }
        val expectedEvExt =
            case.items.sumOf { item ->
                (item.starCost ?: 0L).toDouble() * (item.probabilityPpm.toDouble() / 1_000_000.0)
            }
        val expectedRtp = if (case.priceStars == 0L) 0.0 else expectedEvExt / case.priceStars.toDouble()

        assertEquals(case.id, preview.caseId)
        assertEquals(case.priceStars, preview.priceStars)
        assertEquals(expectedSumPpm, preview.sumPpm)
        assertEquals(expectedEvExt, preview.evExt, 1e-9)
        assertEquals(expectedRtp, preview.rtpExt, 1e-9)
        assertTrue(preview.rtpExt in case.rtpExtMin..case.rtpExtMax)
    }

    @Test
    fun `validate reports problems for invalid configuration`() {
        val invalidCase =
            CaseConfig(
                id = "invalid",
                priceStars = 100,
                rtpExtMin = 0.1,
                rtpExtMax = 0.2,
                jackpotAlpha = 0.5,
                items = listOf(
                    PrizeItemConfig(
                        id = "bad-negative",
                        type = CaseSlotType.GIFT,
                        starCost = -10,
                        probabilityPpm = 600_000,
                    ),
                    PrizeItemConfig(
                        id = "bad-high",
                        type = CaseSlotType.GIFT,
                        starCost = 1_000,
                        probabilityPpm = 300_000,
                    ),
                    PrizeItemConfig(
                        id = "bad-internal",
                        type = CaseSlotType.INTERNAL,
                        probabilityPpm = 200_001,
                    ),
                ),
            )

        val report = CasesYamlLoader.validate(invalidCase)

        assertFalse(report.isOk)
        assertEquals("invalid", report.caseId)
        assertEquals(1_100_001, report.preview.sumPpm)
        assertEquals(294.0, report.preview.evExt, 1e-9)
        assertEquals(2.94, report.preview.rtpExt, 1e-9)
        assertIterableEquals(
            listOf(
                "sumPpm=1100001 > 1_000_000",
                "rtpExt=2.940000 вне коридора [0.100000, 0.200000]",
                "jackpotAlpha=0.500000 вне диапазона [0.0, 0.2]",
                "starCost=-10 < 0 у внешнего приза 'bad-negative'",
            ),
            report.problems,
        )
    }
}
