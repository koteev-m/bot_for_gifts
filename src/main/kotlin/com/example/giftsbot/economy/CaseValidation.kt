package com.example.giftsbot.economy

import kotlinx.serialization.Serializable

@Serializable
data class CasePreview(
    val caseId: String,
    val priceStars: Long,
    val evExt: Double,
    val rtpExt: Double,
    val sumPpm: Int,
    val alpha: Double
)

@Serializable
data class CaseValidationReport(
    val caseId: String,
    val isOk: Boolean,
    val problems: List<String>,
    val preview: CasePreview
)
