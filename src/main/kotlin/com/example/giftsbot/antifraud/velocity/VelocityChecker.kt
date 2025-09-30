package com.example.giftsbot.antifraud.velocity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private const val MILLIS_IN_SECOND = 1000L
private const val DISTINCT_CAPACITY = 16
private const val DISTINCT_LOAD_FACTOR = 0.75f
private const val UA_FLAP_THRESHOLD = 2
private const val INITIAL_UA_TIMESTAMP = 0L
private const val SCORE_MIN = 0
private const val SCORE_MAX = 100
private const val NO_ADDITIONAL_SCORE = 0
private val BOOST_RELEVANT_FLAGS =
    setOf(
        VelocityFlag.FAST_REPEAT_IP_SHORT,
        VelocityFlag.FAST_REPEAT_IP_LONG,
        VelocityFlag.FAST_REPEAT_SUBJECT_SHORT,
        VelocityFlag.FAST_REPEAT_SUBJECT_LONG,
        VelocityFlag.PATH_THRASH_IP,
        VelocityFlag.PATH_THRASH_SUBJECT,
        VelocityFlag.UA_MISMATCH_RECENT,
        VelocityFlag.UA_FLAPPING,
    )

private class RollingWindow(
    private val windowMs: Long,
) {
    private val q = ArrayDeque<Long>()

    fun add(now: Long) {
        q.addLast(now)
        purge(now)
    }

    fun count(now: Long): Int {
        purge(now)
        return q.size
    }

    private fun purge(now: Long) {
        while (q.isNotEmpty() && now - q.first() >= windowMs) {
            q.removeFirst()
        }
    }
}

private class DistinctWindow(
    private val windowMs: Long,
) {
    private val seen = LinkedHashMap<String, Long>(DISTINCT_CAPACITY, DISTINCT_LOAD_FACTOR, true)

    fun add(
        now: Long,
        key: String,
    ) {
        purge(now)
        seen[key] = now
    }

    fun distinctCount(now: Long): Int {
        purge(now)
        return seen.size
    }

    private fun purge(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value >= windowMs) {
                iterator.remove()
            }
        }
    }
}

private data class IpState(
    val shortW: RollingWindow,
    val longW: RollingWindow,
    val pathsShort: DistinctWindow,
    var expiresAtMs: Long,
)

private data class SubjectState(
    val shortW: RollingWindow,
    val longW: RollingWindow,
    val pathsShort: DistinctWindow,
    var lastUa: String?,
    var uaMismatchCount: Int,
    var uaFingerprintSetAtMs: Long,
    var expiresAtMs: Long,
)

private data class IpCounts(
    val short: Int,
    val long: Int,
    val distinctPaths: Int,
)

private data class SubjectCounts(
    val short: Int,
    val long: Int,
    val distinctPaths: Int,
    val uaMismatchCount: Int,
    val lastFingerprintSetAtMs: Long,
)

private data class EventCaps(
    val short: Int,
    val long: Int,
)

private data class WindowDurations(
    val shortMs: Long,
    val longMs: Long,
    val ipTtlMs: Long,
    val subjectTtlMs: Long,
    val uaTtlMs: Long,
)

class VelocityChecker(
    private val config: VelocityConfig = VelocityConfig(),
    private val weights: ScoringWeights = ScoringWeights(),
    private val thresholds: ScoringThresholds = ScoringThresholds(),
    private val clock: Clock = SystemClock,
) {
    private val ipMap = ConcurrentHashMap<String, IpState>()
    private val subjMap = ConcurrentHashMap<Long, SubjectState>()
    private val ipLocks = ConcurrentHashMap<String, Mutex>()
    private val subjLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun checkAndRecord(event: AfEvent): VelocityDecision {
        val now = clock.nowMillis()
        val shortMs = config.shortWindowSec * MILLIS_IN_SECOND
        val longMs = config.longWindowSec * MILLIS_IN_SECOND
        val uaTtlMs = config.uaTtlSeconds * MILLIS_IN_SECOND
        val durations =
            WindowDurations(
                shortMs = shortMs,
                longMs = longMs,
                ipTtlMs = longMs,
                subjectTtlMs = max(longMs, uaTtlMs),
                uaTtlMs = uaTtlMs,
            )
        val ipCounts = updateIpState(event, now, durations)
        val subjectCounts = event.subjectId?.let { updateSubjectState(it, event, now, durations) }
        val flags = evaluateFlags(event, ipCounts, subjectCounts, now, durations.shortMs)
        val score = computeScore(event, flags)
        val action = decideAction(event.type, score)
        return VelocityDecision(score, flags, action)
    }

    private suspend fun updateIpState(
        event: AfEvent,
        now: Long,
        durations: WindowDurations,
    ): IpCounts {
        val mutex = ipLocks.computeIfAbsent(event.ip) { Mutex() }
        return mutex.withLock {
            var state = ipMap[event.ip]
            if (state == null || state.expiresAtMs < now) {
                state =
                    IpState(
                        shortW = RollingWindow(durations.shortMs),
                        longW = RollingWindow(durations.longMs),
                        pathsShort = DistinctWindow(durations.shortMs),
                        expiresAtMs = now + durations.ipTtlMs,
                    )
                ipMap[event.ip] = state
            }
            state.shortW.add(now)
            state.longW.add(now)
            state.pathsShort.add(now, event.path)
            state.expiresAtMs = now + durations.ipTtlMs
            IpCounts(
                short = state.shortW.count(now),
                long = state.longW.count(now),
                distinctPaths = state.pathsShort.distinctCount(now),
            )
        }
    }

    private suspend fun updateSubjectState(
        subjectId: Long,
        event: AfEvent,
        now: Long,
        durations: WindowDurations,
    ): SubjectCounts {
        val mutex = subjLocks.computeIfAbsent(subjectId) { Mutex() }
        return mutex.withLock {
            var state = subjMap[subjectId]
            if (state == null || state.expiresAtMs < now) {
                state =
                    SubjectState(
                        shortW = RollingWindow(durations.shortMs),
                        longW = RollingWindow(durations.longMs),
                        pathsShort = DistinctWindow(durations.shortMs),
                        lastUa = null,
                        uaMismatchCount = 0,
                        uaFingerprintSetAtMs = INITIAL_UA_TIMESTAMP,
                        expiresAtMs = now + durations.subjectTtlMs,
                    )
                subjMap[subjectId] = state
            }
            state.shortW.add(now)
            state.longW.add(now)
            state.pathsShort.add(now, event.path)
            state.expiresAtMs = now + durations.subjectTtlMs
            if (state.uaFingerprintSetAtMs != INITIAL_UA_TIMESTAMP &&
                now - state.uaFingerprintSetAtMs > durations.uaTtlMs
            ) {
                state.lastUa = null
                state.uaMismatchCount = 0
                state.uaFingerprintSetAtMs = INITIAL_UA_TIMESTAMP
            }
            val fingerprint = parseUserAgentFingerprint(event.userAgent)
            if (fingerprint != null) {
                if (state.lastUa == null) {
                    state.lastUa = fingerprint
                    state.uaMismatchCount = 0
                    state.uaFingerprintSetAtMs = now
                } else if (state.lastUa != fingerprint) {
                    val withinTtl =
                        state.uaFingerprintSetAtMs != INITIAL_UA_TIMESTAMP &&
                            now - state.uaFingerprintSetAtMs <= durations.uaTtlMs
                    state.uaMismatchCount = if (withinTtl) state.uaMismatchCount + 1 else 1
                    state.lastUa = fingerprint
                    state.uaFingerprintSetAtMs = now
                }
            }
            SubjectCounts(
                short = state.shortW.count(now),
                long = state.longW.count(now),
                distinctPaths = state.pathsShort.distinctCount(now),
                uaMismatchCount = state.uaMismatchCount,
                lastFingerprintSetAtMs = state.uaFingerprintSetAtMs,
            )
        }
    }

    private fun evaluateFlags(
        event: AfEvent,
        ipCounts: IpCounts,
        subjectCounts: SubjectCounts?,
        now: Long,
        shortMs: Long,
    ): Set<VelocityFlag> {
        val flags = LinkedHashSet<VelocityFlag>()
        val caps = eventCaps(event.type)
        val ipShortCap = max(config.ipShortMax, caps.short)
        val ipLongCap = max(config.ipLongMax, caps.long)
        if (ipCounts.short > ipShortCap) {
            flags.add(VelocityFlag.FAST_REPEAT_IP_SHORT)
        }
        if (ipCounts.long > ipLongCap) {
            flags.add(VelocityFlag.FAST_REPEAT_IP_LONG)
        }
        if (ipCounts.distinctPaths > config.distinctPathsShortMax) {
            flags.add(VelocityFlag.PATH_THRASH_IP)
        }
        if (subjectCounts != null) {
            val subjectShortCap = max(config.subjectShortMax, caps.short)
            val subjectLongCap = max(config.subjectLongMax, caps.long)
            if (subjectCounts.short > subjectShortCap) {
                flags.add(VelocityFlag.FAST_REPEAT_SUBJECT_SHORT)
            }
            if (subjectCounts.long > subjectLongCap) {
                flags.add(VelocityFlag.FAST_REPEAT_SUBJECT_LONG)
            }
            if (subjectCounts.distinctPaths > config.distinctPathsShortMax) {
                flags.add(VelocityFlag.PATH_THRASH_SUBJECT)
            }
            if (subjectCounts.uaMismatchCount >= config.subjectUaMismatchMax) {
                flags.add(VelocityFlag.UA_MISMATCH_RECENT)
            }
            val recentFingerprintChange =
                subjectCounts.lastFingerprintSetAtMs != INITIAL_UA_TIMESTAMP &&
                    now - subjectCounts.lastFingerprintSetAtMs <= shortMs
            if (recentFingerprintChange && subjectCounts.uaMismatchCount >= UA_FLAP_THRESHOLD) {
                flags.add(VelocityFlag.UA_FLAPPING)
            }
        }
        return flags
    }

    private fun computeScore(
        event: AfEvent,
        flags: Set<VelocityFlag>,
    ): Int {
        var score = 0
        for (flag in flags) {
            score +=
                when (flag) {
                    VelocityFlag.FAST_REPEAT_IP_SHORT -> weights.fastIpShort
                    VelocityFlag.FAST_REPEAT_IP_LONG -> weights.fastIpLong
                    VelocityFlag.FAST_REPEAT_SUBJECT_SHORT -> weights.fastSubjShort
                    VelocityFlag.FAST_REPEAT_SUBJECT_LONG -> weights.fastSubjLong
                    VelocityFlag.PATH_THRASH_IP -> weights.pathThrash
                    VelocityFlag.PATH_THRASH_SUBJECT -> weights.pathThrash
                    VelocityFlag.UA_MISMATCH_RECENT -> weights.uaMismatch
                    VelocityFlag.UA_FLAPPING -> weights.uaFlapping
                }
        }
        if (flags.any { it in BOOST_RELEVANT_FLAGS }) {
            val additional =
                when (event.type) {
                    AfEventType.INVOICE -> weights.invoiceBoost
                    AfEventType.PRE_CHECKOUT -> weights.precheckoutBoost
                    else -> NO_ADDITIONAL_SCORE
                }
            score += additional
        }
        return score.coerceIn(SCORE_MIN, SCORE_MAX)
    }

    private fun decideAction(
        type: AfEventType,
        score: Int,
    ): VelocityAction {
        val paymentPhase = type == AfEventType.INVOICE || type == AfEventType.PRE_CHECKOUT
        return when {
            paymentPhase && score >= thresholds.hardBlock -> VelocityAction.HARD_BLOCK_BEFORE_PAYMENT
            score >= thresholds.softCap -> VelocityAction.SOFT_CAP
            else -> VelocityAction.LOG_ONLY
        }
    }

    private fun eventCaps(type: AfEventType): EventCaps =
        when (type) {
            AfEventType.INVOICE -> EventCaps(config.invoiceShortMax, config.invoiceLongMax)
            AfEventType.PRE_CHECKOUT -> EventCaps(config.precheckoutShortMax, config.precheckoutLongMax)
            AfEventType.SUCCESS -> EventCaps(config.successShortMax, config.successLongMax)
            AfEventType.WEBHOOK, AfEventType.API_OTHER -> EventCaps(0, 0)
        }
}
