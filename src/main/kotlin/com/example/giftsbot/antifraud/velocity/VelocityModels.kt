package com.example.giftsbot.antifraud.velocity

enum class AfEventType {
    INVOICE,
    PRE_CHECKOUT,
    SUCCESS,
    WEBHOOK,
    API_OTHER,
}

data class AfEvent(
    val type: AfEventType,
    val ip: String,
    val subjectId: Long?,
    val path: String,
    val userAgent: String?,
    val timestampMs: Long,
)

enum class VelocityFlag {
    FAST_REPEAT_IP_SHORT,
    FAST_REPEAT_IP_LONG,
    FAST_REPEAT_SUBJECT_SHORT,
    FAST_REPEAT_SUBJECT_LONG,
    PATH_THRASH_IP,
    PATH_THRASH_SUBJECT,
    UA_MISMATCH_RECENT,
    UA_FLAPPING,
}

enum class VelocityAction {
    LOG_ONLY,
    SOFT_CAP,
    HARD_BLOCK_BEFORE_PAYMENT,
}

data class VelocityDecision(
    val score: Int,
    val flags: Set<VelocityFlag>,
    val action: VelocityAction,
)

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

data class VelocityConfig(
    val shortWindowSec: Int = 10,
    val longWindowSec: Int = 60,
    val ipShortMax: Int = 20,
    val ipLongMax: Int = 120,
    val subjectShortMax: Int = 10,
    val subjectLongMax: Int = 60,
    val distinctPathsShortMax: Int = 6,
    val uaTtlSeconds: Long = 3600,
    val subjectUaMismatchMax: Int = 3,
    val invoiceShortMax: Int = 5,
    val invoiceLongMax: Int = 20,
    val precheckoutShortMax: Int = 8,
    val precheckoutLongMax: Int = 30,
    val successShortMax: Int = 5,
    val successLongMax: Int = 20,
)

data class ScoringWeights(
    val fastIpShort: Int = 30,
    val fastIpLong: Int = 20,
    val fastSubjShort: Int = 30,
    val fastSubjLong: Int = 20,
    val pathThrash: Int = 15,
    val uaMismatch: Int = 15,
    val uaFlapping: Int = 25,
    val invoiceBoost: Int = 10,
    val precheckoutBoost: Int = 10,
)

data class ScoringThresholds(
    val softCap: Int = 40,
    val hardBlock: Int = 70,
)
