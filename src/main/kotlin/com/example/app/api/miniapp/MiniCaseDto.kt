package com.example.app.api.miniapp

import kotlinx.serialization.Serializable

@Serializable
data class MiniCaseDto(
    val id: String,
    val title: String,
    val priceStars: Int,
    val thumbnail: String,
    val shortDescription: String,
)
