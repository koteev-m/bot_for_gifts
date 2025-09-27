package com.example.app.payments

import com.example.giftsbot.rng.RngDrawRecord
import com.example.giftsbot.rng.RngReceipt

data class AwardPlan(
    val telegramPaymentChargeId: String,
    val providerPaymentChargeId: String?,
    val totalAmount: Long,
    val currency: String,
    val userId: Long,
    val caseId: String,
    val nonce: String,
    val resultItemId: String?,
    val rngRecord: RngDrawRecord,
    val rngReceipt: RngReceipt,
)
