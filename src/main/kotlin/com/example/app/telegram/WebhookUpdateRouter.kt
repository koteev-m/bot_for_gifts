package com.example.app.telegram

import com.example.app.payments.PreCheckoutHandler
import com.example.app.payments.SuccessfulPaymentHandler
import org.slf4j.LoggerFactory

class WebhookUpdateRouter(
    private val preCheckoutHandler: PreCheckoutHandler,
    private val successfulPaymentHandler: SuccessfulPaymentHandler,
) {
    suspend fun route(incoming: IncomingUpdate) {
        when (incoming) {
            is IncomingUpdate.SuccessfulPaymentUpdate -> handleSuccessfulPayment(incoming)
            is IncomingUpdate.PreCheckoutQueryUpdate -> handlePreCheckout(incoming)
            else -> logger.debug(
                "no handler for updateId={} type={}",
                incoming.updateId,
                incoming::class.simpleName,
            )
        }
    }

    private suspend fun handleSuccessfulPayment(incoming: IncomingUpdate.SuccessfulPaymentUpdate) {
        successfulPaymentHandler.handle(incoming.updateId, incoming.message)
    }

    private suspend fun handlePreCheckout(incoming: IncomingUpdate.PreCheckoutQueryUpdate) {
        preCheckoutHandler.handle(incoming.updateId, incoming.query)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(WebhookUpdateRouter::class.java)
    }
}
