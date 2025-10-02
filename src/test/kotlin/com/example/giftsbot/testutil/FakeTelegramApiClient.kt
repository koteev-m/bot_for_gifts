package com.example.giftsbot.testutil

import com.example.giftsbot.telegram.TelegramApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay

class FakeTelegramApiClient(
    private val answerDelayMs: Long = 0L,
) {
    val client: TelegramApiClient = mockk(relaxed = false)

    var lastPreCheckoutAnswer: Pair<String, Boolean>? = null
        private set

    init {
        coEvery { client.answerPreCheckoutQuery(any(), any(), any()) } coAnswers {
            if (answerDelayMs > 0) {
                delay(answerDelayMs)
            }
            val queryId = firstArg<String>()
            val ok = secondArg<Boolean>()
            lastPreCheckoutAnswer = queryId to ok
            true
        }
        coEvery { client.setWebhook(any(), any(), any(), any(), any()) } coAnswers {
            throw UnsupportedOperationException("setWebhook is not supported in FakeTelegramApiClient")
        }
        coEvery { client.deleteWebhook(any()) } coAnswers {
            throw UnsupportedOperationException("deleteWebhook is not supported in FakeTelegramApiClient")
        }
        coEvery { client.getWebhookInfo() } coAnswers {
            throw UnsupportedOperationException("getWebhookInfo is not supported in FakeTelegramApiClient")
        }
        coEvery { client.createInvoiceLink(any()) } coAnswers {
            throw UnsupportedOperationException("createInvoiceLink is not supported in FakeTelegramApiClient")
        }
        coEvery { client.sendMessage(any(), any(), any(), any()) } coAnswers {
            throw UnsupportedOperationException("sendMessage is not supported in FakeTelegramApiClient")
        }
        coEvery { client.getAvailableGifts() } coAnswers {
            throw UnsupportedOperationException("getAvailableGifts is not supported in FakeTelegramApiClient")
        }
        coEvery { client.sendGift(any(), any(), any()) } coAnswers {
            throw UnsupportedOperationException("sendGift is not supported in FakeTelegramApiClient")
        }
        coEvery { client.refundStarPayment(any(), any()) } coAnswers {
            throw UnsupportedOperationException("refundStarPayment is not supported in FakeTelegramApiClient")
        }
        coEvery { client.giftPremiumSubscription(any(), any(), any()) } coAnswers {
            throw UnsupportedOperationException("giftPremiumSubscription is not supported in FakeTelegramApiClient")
        }
        coEvery { client.getUpdates(any(), any(), any()) } coAnswers {
            throw UnsupportedOperationException("getUpdates is not supported in FakeTelegramApiClient")
        }
    }
}
