@file:Suppress("ConstructorParameterNaming")

package com.example.giftsbot.telegram

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
)

@Serializable
data class UpdateDto(
    val update_id: Long,
    val message: MessageDto? = null,
    val pre_checkout_query: PreCheckoutQueryDto? = null,
    val successful_payment: SuccessfulPaymentDto? = null,
)

@Serializable
data class MessageDto(
    val message_id: Long,
    val date: Long,
    val chat: ChatDto,
    val from: UserDto? = null,
    val text: String? = null,
    val successful_payment: SuccessfulPaymentDto? = null,
)

@Serializable
data class ChatDto(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    val first_name: String? = null,
    val last_name: String? = null,
)

@Serializable
data class UserDto(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String,
    val last_name: String? = null,
    val username: String? = null,
    val language_code: String? = null,
)

@Serializable
data class PreCheckoutQueryDto(
    val id: String,
    val from: UserDto,
    val currency: String,
    val total_amount: Long,
    val invoice_payload: String,
    val shipping_option_id: String? = null,
    val order_info: OrderInfoDto? = null,
)

@Serializable
data class SuccessfulPaymentDto(
    val currency: String,
    val total_amount: Long,
    val invoice_payload: String,
    val shipping_option_id: String? = null,
    val order_info: OrderInfoDto? = null,
    val telegram_payment_charge_id: String,
    val provider_payment_charge_id: String,
)

@Serializable
data class OrderInfoDto(
    val name: String? = null,
    val phone_number: String? = null,
    val email: String? = null,
    val shipping_address: ShippingAddressDto? = null,
)

@Serializable
data class ShippingAddressDto(
    val country_code: String,
    val state: String? = null,
    val city: String,
    val street_line1: String,
    val street_line2: String,
    val post_code: String,
)

@Serializable
data class WebhookInfoDto(
    val url: String,
    val has_custom_certificate: Boolean,
    val pending_update_count: Int,
    val ip_address: String? = null,
    val last_error_date: Int? = null,
    val last_error_message: String? = null,
    val last_synchronization_error_date: Int? = null,
    val max_connections: Int? = null,
    val allowed_updates: List<String>? = null,
)
