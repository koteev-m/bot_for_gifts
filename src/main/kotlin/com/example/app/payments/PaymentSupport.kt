package com.example.app.payments

data class PaymentSupport(
    val config: PaymentsConfig,
    val refundService: RefundService,
)
