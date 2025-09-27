package com.example.app.payments

import com.example.app.payments.dto.PaymentPayload
import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.telegram.CreateInvoiceLinkRequest
import com.example.giftsbot.telegram.TelegramApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramInvoiceServiceTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2024-02-03T04:05:06Z"), ZoneOffset.UTC)

    @Test
    fun `creates invoice link for case`() =
        runTest {
            val casesRepository = mockk<CasesRepository>()
            val telegramApiClient = mockk<TelegramApiClient>()
            val paymentsConfig =
                PaymentsConfig(
                    currency = STARS_CURRENCY_CODE,
                    titlePrefix = "GiftBot",
                    receiptEnabled = true,
                    businessConnectionId = "bc-42",
                )
            val service = TelegramInvoiceService(casesRepository, telegramApiClient, paymentsConfig, clock)

            val case =
                CaseConfig(
                    id = "micro",
                    title = "Micro Case",
                    priceStars = 29,
                    rtpExtMin = 0.3,
                    rtpExtMax = 0.4,
                    jackpotAlpha = 0.01,
                    items = emptyList(),
                )
            every { casesRepository.get("micro") } returns case
            val requestSlot = slot<CreateInvoiceLinkRequest>()
            coEvery { telegramApiClient.createInvoiceLink(capture(requestSlot)) } returns
                "https://t.me/invoice?start=abc"

            val response = service.createCaseInvoice("micro", userId = 1234L, nonce = "nonce123")

            assertEquals("https://t.me/invoice?start=abc", response.invoiceLink)
            val payload = PaymentPayload.decode(response.payload)
            assertEquals("micro", payload.caseId)
            assertEquals(1234L, payload.userId)
            assertEquals("nonce123", payload.nonce)
            assertEquals(clock.instant().toEpochMilli(), payload.ts)

            val request = requestSlot.captured
            assertEquals("GiftBot Micro Case".take(32), request.title)
            assertEquals("Micro Case", request.description)
            assertEquals(STARS_CURRENCY_CODE, request.currency)
            assertEquals(response.payload, request.payload)
            assertEquals(1, request.prices.size)
            val price = request.prices.single()
            assertEquals(case.priceStars, price.amount)
            assertEquals(case.title, price.label)
            assertEquals(paymentsConfig.businessConnectionId, request.businessConnectionId)
            assertEquals(true, request.receiptEnabled)
            assertNull(request.providerToken)

            coVerify(exactly = 1) { telegramApiClient.createInvoiceLink(any()) }
        }

    @Test
    fun `throws when case is missing`() =
        runTest {
            val casesRepository = mockk<CasesRepository>()
            every { casesRepository.get("missing") } returns null
            val telegramApiClient = mockk<TelegramApiClient>(relaxed = true)
            val paymentsConfig =
                PaymentsConfig(
                    currency = STARS_CURRENCY_CODE,
                    titlePrefix = null,
                    receiptEnabled = false,
                    businessConnectionId = null,
                )
            val service = TelegramInvoiceService(casesRepository, telegramApiClient, paymentsConfig, clock)

            val error =
                assertFailsWith<IllegalArgumentException> {
                    service.createCaseInvoice("missing", userId = 1L, nonce = "nonce")
                }
            assertTrue(error.message?.contains("missing") == true)

            coVerify(exactly = 0) { telegramApiClient.createInvoiceLink(any()) }
        }
}
