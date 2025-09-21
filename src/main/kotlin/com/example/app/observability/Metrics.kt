package com.example.app.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object MetricsNames {
    const val WEBHOOK_UPDATES_TOTAL = "tg_webhook_updates_total"
    const val WEBHOOK_REJECTED_TOTAL = "tg_webhook_rejected_total"
    const val WEBHOOK_TOO_LARGE_TOTAL = "tg_webhook_body_too_large_total"
    const val WEBHOOK_ENQUEUE_SECONDS = "tg_webhook_enqueue_seconds"

    const val QUEUE_SIZE_GAUGE = "tg_queue_size"
    const val UPDATES_ENQUEUED_TOTAL = "tg_updates_enqueued_total"
    const val UPDATES_DUPLICATED_TOTAL = "tg_updates_duplicated_total"
    const val UPDATES_DROPPED_TOTAL = "tg_updates_dropped_total"
    const val UPDATE_HANDLE_SECONDS = "tg_update_handle_seconds"
    const val UPDATES_PROCESSED_TOTAL = "tg_updates_processed_total"

    const val LP_REQUESTS_TOTAL = "tg_lp_requests_total"
    const val LP_RESPONSES_TOTAL = "tg_lp_responses_total"
    const val LP_BATCHES_TOTAL = "tg_lp_batches_total"
    const val LP_UPDATES_TOTAL = "tg_lp_updates_total"

    @Suppress("ktlint:standard:property-naming")
    const val LP_ERRORS_total = "tg_lp_errors_total"
    const val LP_RETRIES_TOTAL = "tg_lp_retries_total"
    const val LP_REQUEST_SECONDS = "tg_lp_request_seconds"
    const val LP_CYCLES_TOTAL = "tg_lp_cycles_total"
    const val LP_OFFSET_CURRENT = "tg_lp_offset_current"

    const val ADMIN_SET_TOTAL = "tg_admin_webhook_set_total"
    const val ADMIN_DELETE_TOTAL = "tg_admin_webhook_delete_total"
    const val ADMIN_INFO_TOTAL = "tg_admin_webhook_info_total"
    const val ADMIN_FAIL_TOTAL = "tg_admin_webhook_fail_total"
}

object MetricsTags {
    const val COMPONENT = "component"
    const val RESULT = "result"
}

private typealias MetricTag = Pair<String, String>

private data class MetricKey(
    val name: String,
    val tags: List<MetricTag>,
)

object Metrics {
    private val counters = ConcurrentHashMap<MetricKey, Counter>()
    private val timers = ConcurrentHashMap<MetricKey, Timer>()
    private val longGauges = ConcurrentHashMap<MetricKey, AtomicLong>()
    private val intGauges = ConcurrentHashMap<MetricKey, AtomicInteger>()

    fun counter(
        registry: MeterRegistry,
        name: String,
        vararg tags: MetricTag,
    ): Counter {
        val normalizedTags = tags.toList()
        val key = MetricKey(name, normalizedTags)
        return counters.computeIfAbsent(key) {
            Counter
                .builder(name)
                .tags(toTags(normalizedTags))
                .register(registry)
        }
    }

    fun timer(
        registry: MeterRegistry,
        name: String,
        vararg tags: MetricTag,
    ): Timer {
        val normalizedTags = tags.toList()
        val key = MetricKey(name, normalizedTags)
        return timers.computeIfAbsent(key) {
            Timer
                .builder(name)
                .tags(toTags(normalizedTags))
                .register(registry)
        }
    }

    fun gaugeLong(
        registry: MeterRegistry,
        name: String,
        state: AtomicLong,
        vararg tags: MetricTag,
    ): AtomicLong {
        val normalizedTags = tags.toList()
        val key = MetricKey(name, normalizedTags)
        longGauges.computeIfAbsent(key) {
            Gauge
                .builder(name, state) { value -> value.get().toDouble() }
                .tags(toTags(normalizedTags))
                .register(registry)
            state
        }
        return state
    }

    fun gaugeInt(
        registry: MeterRegistry,
        name: String,
        state: AtomicInteger,
        vararg tags: MetricTag,
    ): AtomicInteger {
        val normalizedTags = tags.toList()
        val key = MetricKey(name, normalizedTags)
        intGauges.computeIfAbsent(key) {
            Gauge
                .builder(name, state) { value -> value.get().toDouble() }
                .tags(toTags(normalizedTags))
                .register(registry)
            state
        }
        return state
    }

    private fun toTags(tags: List<MetricTag>): List<Tag> = tags.map { (key, value) -> Tag.of(key, value) }
}
