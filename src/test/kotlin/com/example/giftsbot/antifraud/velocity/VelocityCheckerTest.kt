package com.example.giftsbot.antifraud.velocity

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VelocityCheckerTest {
    @Test
    fun fastRepeatIpSetsFlagsAndHardBlock() =
        runTest {
            val clock = MutableTestClock()
            val config =
                VelocityConfig(
                    ipShortMax = 2,
                    ipLongMax = 4,
                    invoiceShortMax = 2,
                    invoiceLongMax = 4,
                )
            val thresholds = ScoringThresholds(softCap = 40, hardBlock = 55)
            val checker = VelocityChecker(config = config, thresholds = thresholds, clock = clock)
            var decision =
                checker.checkAndRecord(
                    event(clock, EventArgs(type = AfEventType.INVOICE, ip = "10.0.0.1")),
                )
            repeat(4) {
                clock.advanceBy(1000L)
                decision =
                    checker.checkAndRecord(
                        event(clock, EventArgs(type = AfEventType.INVOICE, ip = "10.0.0.1")),
                    )
            }
            assertTrue(decision.flags.contains(VelocityFlag.FAST_REPEAT_IP_SHORT))
            assertTrue(decision.flags.contains(VelocityFlag.FAST_REPEAT_IP_LONG))
            assertEquals(VelocityAction.HARD_BLOCK_BEFORE_PAYMENT, decision.action)
        }

    @Test
    fun subjectFlagsRequireSubjectId() =
        runTest {
            val clock = MutableTestClock()
            val config =
                VelocityConfig(
                    subjectShortMax = 1,
                    subjectLongMax = 2,
                    successShortMax = 1,
                    successLongMax = 2,
                )
            val checker = VelocityChecker(config = config, clock = clock)
            var decision =
                checker.checkAndRecord(
                    event(clock, EventArgs(type = AfEventType.SUCCESS, ip = "192.168.1.5")),
                )
            repeat(3) {
                clock.advanceBy(500L)
                decision =
                    checker.checkAndRecord(
                        event(clock, EventArgs(type = AfEventType.SUCCESS, ip = "192.168.1.5")),
                    )
            }
            assertFalse(decision.flags.contains(VelocityFlag.FAST_REPEAT_SUBJECT_SHORT))
            assertFalse(decision.flags.contains(VelocityFlag.FAST_REPEAT_SUBJECT_LONG))
            clock.advanceBy(500L)
            repeat(4) {
                decision =
                    checker.checkAndRecord(
                        event(
                            clock,
                            EventArgs(
                                type = AfEventType.SUCCESS,
                                ip = "192.168.1.5",
                                subjectId = 42L,
                            ),
                        ),
                    )
                clock.advanceBy(500L)
            }
            assertTrue(decision.flags.contains(VelocityFlag.FAST_REPEAT_SUBJECT_SHORT))
            assertTrue(decision.flags.contains(VelocityFlag.FAST_REPEAT_SUBJECT_LONG))
        }

    @Test
    fun pathThrashFlagsIpAndSubject() =
        runTest {
            val clock = MutableTestClock()
            val config = VelocityConfig(distinctPathsShortMax = 2)
            val checker = VelocityChecker(config = config, clock = clock)
            var decision: VelocityDecision? = null
            repeat(4) { idx ->
                decision =
                    checker.checkAndRecord(
                        event(
                            clock,
                            EventArgs(
                                type = AfEventType.API_OTHER,
                                ip = "172.16.0.1",
                                subjectId = 7L,
                                path = "/path/$idx",
                            ),
                        ),
                    )
                clock.advanceBy(500L)
            }
            val finalDecision = requireNotNull(decision)
            assertTrue(finalDecision.flags.contains(VelocityFlag.PATH_THRASH_IP))
            assertTrue(finalDecision.flags.contains(VelocityFlag.PATH_THRASH_SUBJECT))
        }

    @Test
    fun uaMismatchAndFlappingAreDetected() =
        runTest {
            val clock = MutableTestClock()
            val config = VelocityConfig(subjectUaMismatchMax = 3)
            val checker = VelocityChecker(config = config, clock = clock)
            checker.checkAndRecord(
                event(
                    clock,
                    EventArgs(
                        type = AfEventType.SUCCESS,
                        ip = "8.8.8.8",
                        subjectId = 100L,
                        userAgent = "Mozilla/5.0 Chrome/120.0",
                    ),
                ),
            )
            clock.advanceBy(500L)
            checker.checkAndRecord(
                event(
                    clock,
                    EventArgs(
                        type = AfEventType.SUCCESS,
                        ip = "8.8.8.8",
                        subjectId = 100L,
                        userAgent = "Mozilla/5.0 Chrome/121.0",
                    ),
                ),
            )
            clock.advanceBy(500L)
            checker.checkAndRecord(
                event(
                    clock,
                    EventArgs(
                        type = AfEventType.SUCCESS,
                        ip = "8.8.8.8",
                        subjectId = 100L,
                        userAgent = "Mozilla/5.0 Firefox/128.0",
                    ),
                ),
            )
            clock.advanceBy(500L)
            val decision =
                checker.checkAndRecord(
                    event(
                        clock,
                        EventArgs(
                            type = AfEventType.SUCCESS,
                            ip = "8.8.8.8",
                            subjectId = 100L,
                            userAgent = "Mozilla/5.0 (Macintosh) Version/17.4 Safari/605.1.15",
                        ),
                    ),
                )
            assertTrue(decision.flags.contains(VelocityFlag.UA_FLAPPING))
            assertTrue(decision.flags.contains(VelocityFlag.UA_MISMATCH_RECENT))
        }

    @Test
    fun scoringAccumulatesAndCaps() =
        runTest {
            val clock = MutableTestClock()
            val config =
                VelocityConfig(
                    ipShortMax = 1,
                    subjectShortMax = 1,
                    distinctPathsShortMax = 1,
                    subjectUaMismatchMax = 2,
                    precheckoutShortMax = 1,
                    precheckoutLongMax = 2,
                )
            val checker = VelocityChecker(config = config, clock = clock)
            val subjectId = 501L
            val ip = "203.0.113.5"
            val userAgents =
                listOf(
                    "Mozilla/5.0 Chrome/120.0",
                    "Mozilla/5.0 Chrome/121.0",
                    "Mozilla/5.0 Firefox/128.0",
                )
            var decision: VelocityDecision? = null
            userAgents.forEachIndexed { index, ua ->
                decision =
                    checker.checkAndRecord(
                        event(
                            clock,
                            EventArgs(
                                type = AfEventType.PRE_CHECKOUT,
                                ip = ip,
                                subjectId = subjectId,
                                path = "/$index",
                                userAgent = ua,
                            ),
                        ),
                    )
                clock.advanceBy(500L)
            }
            val finalDecision = requireNotNull(decision)
            assertEquals(100, finalDecision.score)
            assertEquals(VelocityAction.HARD_BLOCK_BEFORE_PAYMENT, finalDecision.action)
            assertTrue(finalDecision.flags.contains(VelocityFlag.FAST_REPEAT_IP_SHORT))
            assertTrue(finalDecision.flags.contains(VelocityFlag.FAST_REPEAT_SUBJECT_SHORT))
            assertTrue(finalDecision.flags.contains(VelocityFlag.PATH_THRASH_IP))
            assertTrue(finalDecision.flags.contains(VelocityFlag.PATH_THRASH_SUBJECT))
            assertTrue(finalDecision.flags.contains(VelocityFlag.UA_FLAPPING))
            assertTrue(finalDecision.flags.contains(VelocityFlag.UA_MISMATCH_RECENT))
        }

    @Test
    fun stateExpiresAfterTtl() =
        runTest {
            val clock = MutableTestClock()
            val config =
                VelocityConfig(
                    longWindowSec = 5,
                    ipShortMax = 1,
                    subjectShortMax = 1,
                    uaTtlSeconds = 2,
                )
            val checker = VelocityChecker(config = config, clock = clock)
            checker.checkAndRecord(
                event(
                    clock,
                    EventArgs(
                        type = AfEventType.INVOICE,
                        ip = "10.10.10.10",
                        subjectId = 77L,
                        userAgent = "Mozilla/5.0 Chrome/120.0",
                    ),
                ),
            )
            clock.advanceBy(6000L)
            val decision =
                checker.checkAndRecord(
                    event(
                        clock,
                        EventArgs(
                            type = AfEventType.INVOICE,
                            ip = "10.10.10.10",
                            subjectId = 77L,
                            userAgent = "Mozilla/5.0 Firefox/128.0",
                        ),
                    ),
                )
            assertFalse(decision.flags.contains(VelocityFlag.FAST_REPEAT_IP_SHORT))
            assertFalse(decision.flags.contains(VelocityFlag.FAST_REPEAT_SUBJECT_SHORT))
            assertFalse(decision.flags.contains(VelocityFlag.UA_MISMATCH_RECENT))
        }
}

private class MutableTestClock(
    initial: Long = 0L,
) : Clock {
    private var current = initial

    override fun nowMillis(): Long = current

    fun advanceBy(delta: Long) {
        current += delta
    }
}

private data class EventArgs(
    val type: AfEventType,
    val ip: String,
    val subjectId: Long? = null,
    val path: String = "/default",
    val userAgent: String? = null,
)

private fun event(
    clock: MutableTestClock,
    args: EventArgs,
): AfEvent =
    AfEvent(
        type = args.type,
        ip = args.ip,
        subjectId = args.subjectId,
        path = args.path,
        userAgent = args.userAgent,
        timestampMs = clock.nowMillis(),
    )
