package com.example.giftsbot.economy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CaseSlotType {
    @SerialName("PREMIUM_3M")
    PREMIUM_3M,

    @SerialName("PREMIUM_6M")
    PREMIUM_6M,

    @SerialName("PREMIUM_12M")
    PREMIUM_12M,

    @SerialName("GIFT")
    GIFT,

    @SerialName("INTERNAL")
    INTERNAL,
}

@Serializable
data class PrizeItemConfig(
    val id: String,
    val type: CaseSlotType,
    val starCost: Long? = null,
    val probabilityPpm: Int,
)

@Serializable
data class CaseConfig(
    val id: String,
    val title: String = id,
    val priceStars: Long,
    val rtpExtMin: Double,
    val rtpExtMax: Double,
    val jackpotAlpha: Double,
    val items: List<PrizeItemConfig>,
)

@Serializable
data class CasesRoot(
    val cases: List<CaseConfig>,
)

@Serializable
data class PublicCaseDto(
    val id: String,
    val title: String,
    val priceStars: Long,
    val thumbnail: String? = null,
)
