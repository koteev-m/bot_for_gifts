package com.example.app.payments

import com.example.app.payments.dto.CreateCaseInvoiceResponse
import com.example.app.payments.dto.PaymentPayload
import com.example.giftsbot.economy.CaseConfig
import com.example.giftsbot.economy.CasesRepository
import com.example.giftsbot.telegram.CreateInvoiceLinkRequest
import com.example.giftsbot.telegram.LabeledPrice
import com.example.giftsbot.telegram.TelegramApiClient
import org.slf4j.LoggerFactory
import java.time.Clock

class TelegramInvoiceService(
    private val casesRepository: CasesRepository,
    private val telegramApiClient: TelegramApiClient,
    private val paymentsConfig: PaymentsConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun createCaseInvoice(
        caseId: String,
        userId: Long,
        nonce: String,
    ): CreateCaseInvoiceResponse {
        val case = caseOrFail(caseId)
        val payload =
            PaymentPayload(
                caseId = caseId,
                userId = userId,
                nonce = nonce,
                ts = clock.instant().toEpochMilli(),
            )
        val encodedPayload = payload.encode()

        val title = invoiceTitle(case.title)
        val description = invoiceDescription(case.title)
        val prices = listOf(LabeledPrice(label = case.title, amount = case.priceStars))

        logger.info(
            "Creating case invoice caseId={} userId={} stars={} receiptEnabled={} " +
                "businessConnection={} payloadSize={}",
            caseId,
            userId,
            case.priceStars,
            paymentsConfig.receiptEnabled,
            paymentsConfig.businessConnectionId != null,
            encodedPayload.length,
        )

        val invoiceLink =
            telegramApiClient.createInvoiceLink(
                CreateInvoiceLinkRequest(
                    title = title,
                    description = description,
                    payload = encodedPayload,
                    currency = STARS_CURRENCY_CODE,
                    prices = prices,
                    businessConnectionId = paymentsConfig.businessConnectionId,
                    receiptEnabled = paymentsConfig.receiptEnabled.takeIf { it },
                ),
            )

        return CreateCaseInvoiceResponse(invoiceLink = invoiceLink, payload = encodedPayload)
    }

    private fun caseOrFail(caseId: String): CaseConfig =
        casesRepository.get(caseId)
            ?: throw IllegalArgumentException("Case '$caseId' is not available")

    private fun invoiceTitle(caseTitle: String): String {
        val prefix = paymentsConfig.titlePrefix?.trim()?.takeUnless { it.isEmpty() }
        val combined = listOfNotNull(prefix, caseTitle).joinToString(separator = " ")
        val normalized = (combined.ifBlank { caseTitle }).trim()
        return normalized.take(MAX_TITLE_LENGTH)
    }

    private fun invoiceDescription(caseTitle: String): String {
        val normalized = caseTitle.trim().ifEmpty { caseTitle }
        return normalized.take(MAX_DESCRIPTION_LENGTH)
    }

    companion object {
        private const val MAX_TITLE_LENGTH = 32
        private const val MAX_DESCRIPTION_LENGTH = 255
        private val logger = LoggerFactory.getLogger(TelegramInvoiceService::class.java)
    }
}
