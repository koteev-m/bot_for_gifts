package com.example.app.payments.dto

import com.example.app.payments.STARS_CURRENCY_CODE
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CreateCaseInvoiceRequest(
    val caseId: String,
    val userId: Long,
    val nonce: String,
)

@Serializable
data class CreateCaseInvoiceResponse(
    val invoiceLink: String,
    val payload: String,
)

@Serializable
data class PaymentPayload(
    val caseId: String,
    val userId: Long,
    val nonce: String,
    val ts: Long,
) {
    init {
        val encoded = defaultJson.encodeToString(serializer(), this)
        ensureFits(encoded)
    }

    fun encode(json: Json = defaultJson): String {
        val encoded = json.encodeToString(serializer(), this)
        ensureFits(encoded)
        return encoded
    }

    companion object {
        private const val MAX_PAYLOAD_BYTES: Int = 128
        private val defaultJson: Json =
            Json {
                encodeDefaults = false
                explicitNulls = false
            }

        fun decode(
            raw: String,
            json: Json = defaultJson,
        ): PaymentPayload {
            ensureFits(raw)
            return json.decodeFromString(serializer(), raw)
        }

        private fun ensureFits(raw: String) {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
                "Payment payload size exceeds $MAX_PAYLOAD_BYTES bytes"
            }
        }
    }
}

@Serializable
data class PaymentResult(
    val ok: Boolean,
    val telegramChargeId: String,
    val providerChargeId: String? = null,
    val totalAmount: Long,
    val currency: String,
) {
    init {
        require(currency == STARS_CURRENCY_CODE) {
            "Unsupported payment currency: $currency"
        }
    }
}
