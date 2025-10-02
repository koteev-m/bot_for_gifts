package com.example.giftsbot.antifraud.velocity

import com.example.giftsbot.antifraud.velocity.AfEventType.INVOICE
import com.example.giftsbot.antifraud.velocity.AfEventType.PRE_CHECKOUT
import com.example.giftsbot.antifraud.velocity.AfEventType.SUCCESS
import com.example.giftsbot.antifraud.velocity.AfEventType.WEBHOOK
import com.example.giftsbot.antifraud.velocity.ScoringThresholds
import com.example.giftsbot.testutil.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class VelocityCheckerIT {
    @Test
    fun `invoice spike triggers hard block`() =
        runTest {
            val clock = TestClock()
            val checker = tunedChecker(clock)
            val event = event(INVOICE, "203.0.113.10", 10L, "/api/miniapp/invoice", clock)

            val first = checker.checkAndRecord(event)
            assertEquals(VelocityAction.LOG_ONLY, first.action)

            val second = checker.checkAndRecord(event)
            assertEquals(VelocityAction.HARD_BLOCK_BEFORE_PAYMENT, second.action)
        }

    @Test
    fun `path thrash yields elevated action`() =
        runTest {
            val clock = TestClock()
            val checker = tunedChecker(clock)
            val ip = "203.0.113.20"
            val subject = 20L

            checker.checkAndRecord(event(INVOICE, ip, subject, "/api/miniapp/invoice", clock))
            checker.checkAndRecord(event(PRE_CHECKOUT, ip, subject, "/telegram/pre-checkout", clock))
            val thrash = checker.checkAndRecord(event(WEBHOOK, ip, subject, "/telegram/webhook", clock))

            assertNotEquals(VelocityAction.LOG_ONLY, thrash.action)
        }

    @Test
    fun `success events never hard block`() =
        runTest {
            val clock = TestClock()
            val checker = tunedChecker(clock)
            val ip = "203.0.113.30"
            val subject = 30L

            repeat(3) {
                checker.checkAndRecord(event(INVOICE, ip, subject, "/api/miniapp/invoice", clock))
            }
            val success = checker.checkAndRecord(event(SUCCESS, ip, subject, "/telegram/success", clock))

            assertNotEquals(VelocityAction.HARD_BLOCK_BEFORE_PAYMENT, success.action)
        }

    private fun tunedChecker(clock: TestClock): VelocityChecker =
        VelocityChecker(
            config =
                VelocityConfig(
                    shortWindowSec = 60,
                    longWindowSec = 60,
                    ipShortMax = 1,
                    ipLongMax = 1,
                    subjectShortMax = 1,
                    subjectLongMax = 1,
                    distinctPathsShortMax = 1,
                    uaTtlSeconds = 3600,
                    subjectUaMismatchMax = 2,
                    invoiceShortMax = 1,
                    invoiceLongMax = 1,
                    precheckoutShortMax = 1,
                    precheckoutLongMax = 1,
                    successShortMax = 1,
                    successLongMax = 1,
                ),
            thresholds = ScoringThresholds(softCap = 10, hardBlock = 20),
            clock = clock,
        )

    private fun event(
        type: AfEventType,
        ip: String,
        subjectId: Long?,
        path: String,
        clock: TestClock,
    ): AfEvent =
        AfEvent(
            type = type,
            ip = ip,
            subjectId = subjectId,
            path = path,
            userAgent = "agent",
            timestampMs = clock.nowMillis(),
        )
}
