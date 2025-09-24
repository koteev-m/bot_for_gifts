package com.example.app.telegram

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.testutil.JsonSamples
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDispatcherTest {
    @Test
    fun `single update is enqueued and processed`() =
        runTest(UnconfinedTestDispatcher()) {
            resetMetrics()
            val meterRegistry = SimpleMeterRegistry()
            val dispatcher =
                UpdateDispatcher(
                    scope = this,
                    meterRegistry = meterRegistry,
                    queueCapacity = 4,
                )

            try {
                dispatcher.start()

                dispatcher.enqueue(JsonSamples.dto(1))

                dispatcher.close()

                assertEquals(1.0, meterRegistry.queueCounter(MetricsNames.UPDATES_ENQUEUED_TOTAL))
                assertEquals(1.0, meterRegistry.queueCounter(MetricsNames.UPDATES_PROCESSED_TOTAL))
                assertEquals(0.0, meterRegistry.queueCounter(MetricsNames.UPDATES_DUPLICATED_TOTAL))
                assertEquals(0.0, meterRegistry.queueCounter(MetricsNames.UPDATES_DROPPED_TOTAL))
            } finally {
                dispatcher.close()
            }
        }

    @Test
    fun `duplicate update is not enqueued`() =
        runTest(UnconfinedTestDispatcher()) {
            resetMetrics()
            val meterRegistry = SimpleMeterRegistry()
            val dispatcher =
                UpdateDispatcher(
                    scope = this,
                    meterRegistry = meterRegistry,
                    queueCapacity = 4,
                )

            try {
                dispatcher.start()

                val update = JsonSamples.dto(2)
                dispatcher.enqueue(update)
                dispatcher.enqueue(update)

                dispatcher.close()

                assertEquals(1.0, meterRegistry.queueCounter(MetricsNames.UPDATES_ENQUEUED_TOTAL))
                assertEquals(1.0, meterRegistry.queueCounter(MetricsNames.UPDATES_PROCESSED_TOTAL))
                assertEquals(1.0, meterRegistry.queueCounter(MetricsNames.UPDATES_DUPLICATED_TOTAL))
                assertEquals(0.0, meterRegistry.queueCounter(MetricsNames.UPDATES_DROPPED_TOTAL))
            } finally {
                dispatcher.close()
            }
        }

    @Test
    fun `queue overflow drops exactly one update`() =
        runTest(UnconfinedTestDispatcher()) {
            resetMetrics()
            val meterRegistry = SimpleMeterRegistry()
            val dispatcher =
                UpdateDispatcher(
                    scope = this,
                    meterRegistry = meterRegistry,
                    queueCapacity = 2,
                )

            try {
                dispatcher.enqueue(JsonSamples.dto(10))
                dispatcher.enqueue(JsonSamples.dto(11))
                dispatcher.enqueue(JsonSamples.dto(12))

                dispatcher.start()
                dispatcher.close()

                assertEquals(3.0, meterRegistry.queueCounter(MetricsNames.UPDATES_ENQUEUED_TOTAL))
                assertEquals(2.0, meterRegistry.queueCounter(MetricsNames.UPDATES_PROCESSED_TOTAL))
                assertEquals(0.0, meterRegistry.queueCounter(MetricsNames.UPDATES_DUPLICATED_TOTAL))
                assertEquals(1.0, meterRegistry.queueCounter(MetricsNames.UPDATES_DROPPED_TOTAL))
            } finally {
                dispatcher.close()
            }
        }

    @Test
    fun `close completes workers and cleanup job`() =
        runTest(UnconfinedTestDispatcher()) {
            resetMetrics()
            val meterRegistry = SimpleMeterRegistry()
            val parentJob = SupervisorJob()
            val scope = CoroutineScope(parentJob)
            val dispatcher =
                UpdateDispatcher(
                    scope = scope,
                    meterRegistry = meterRegistry,
                    queueCapacity = 4,
                    workers = 2,
                )

            val dropBeforeStart = meterRegistry.queueCounter(MetricsNames.UPDATES_DROPPED_TOTAL)

            dispatcher.start()
            dispatcher.enqueue(JsonSamples.dto(20))

            withTimeout(5_000) {
                dispatcher.close()
            }

            assertTrue(parentJob.children.none())

            dispatcher.enqueue(JsonSamples.dto(21))
            val dropAfterClose = meterRegistry.queueCounter(MetricsNames.UPDATES_DROPPED_TOTAL)
            assertEquals(dropBeforeStart + 1.0, dropAfterClose)

            parentJob.cancel()
        }

    private fun MeterRegistry.queueCounter(name: String): Double =
        Metrics
            .counter(this, name, MetricsTags.COMPONENT to QUEUE_COMPONENT)
            .count()

    private fun resetMetrics() {
        val metricsClass = Metrics::class.java
        listOf("counters", "timers", "longGauges", "intGauges").forEach { fieldName ->
            val field = metricsClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val map = field.get(Metrics) as? MutableMap<*, *>
            map?.clear()
        }
    }

    private companion object {
        private const val QUEUE_COMPONENT = "queue"
    }
}
