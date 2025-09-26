package com.example.app.payments

import com.example.app.util.configValue
import io.ktor.server.application.Application

const val STARS_CURRENCY_CODE: String = "XTR"

data class PaymentsConfig(
    val currency: String,
    val titlePrefix: String?,
    val receiptEnabled: Boolean,
    val businessConnectionId: String?,
)

fun Application.loadPaymentsConfig(): PaymentsConfig {
    val currency =
        configValue(
            propertyKeys = listOf("pay.currency", "payments.currency"),
            envKeys = listOf("PAY_CURRENCY"),
            configKeys = listOf("app.payments.currency", "payments.currency"),
        )?.trim()?.uppercase()?.takeUnless { it.isEmpty() } ?: STARS_CURRENCY_CODE

    require(currency == STARS_CURRENCY_CODE) {
        "Unsupported payment currency: $currency"
    }

    val titlePrefix =
        configValue(
            propertyKeys = listOf("pay.titlePrefix", "payments.titlePrefix"),
            envKeys = listOf("PAY_TITLE_PREFIX"),
            configKeys = listOf("app.payments.titlePrefix", "payments.titlePrefix"),
        )?.trim()?.takeUnless { it.isEmpty() }

    val receiptEnabled =
        configValue(
            propertyKeys = listOf("pay.receiptEnabled", "payments.receiptEnabled"),
            envKeys = listOf("PAY_RECEIPT_ENABLE"),
            configKeys = listOf("app.payments.receiptEnabled", "payments.receiptEnabled"),
        )?.trim()?.lowercase()?.takeUnless { it.isEmpty() }?.let { normalized ->
            when (normalized) {
                "true" -> true
                "false" -> false
                else -> error("Invalid PAY_RECEIPT_ENABLE value: $normalized")
            }
        } ?: false

    val businessConnectionId =
        configValue(
            propertyKeys = listOf("pay.businessConnectionId", "payments.businessConnectionId"),
            envKeys = listOf("PAY_BUSINESS_CONNECTION_ID"),
            configKeys = listOf("app.payments.businessConnectionId", "payments.businessConnectionId"),
        )?.trim()?.takeUnless { it.isEmpty() }

    return PaymentsConfig(
        currency = currency,
        titlePrefix = titlePrefix,
        receiptEnabled = receiptEnabled,
        businessConnectionId = businessConnectionId,
    )
}
