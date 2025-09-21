package com.example.giftsbot.telegram

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.telegram.UpdateSink
import com.example.app.telegram.dto.UpdateDto
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

private const val MIN_TIMEOUT_SECONDS = 1
private const val MAX_TIMEOUT_SECONDS = 50
private const val INITIAL_BACKOFF_DELAY_MS = 200L
private const val MAX_BACKOFF_DELAY_MS = 1_600L
private const val JITTER_RATIO = 0.1
private const val UNINITIALIZED_OFFSET = -1L
private const val DEFAULT_MAX_ATTEMPTS = 4
private const val LP_COMPONENT = "lp"

@Suppress("LongParameterList")
class LongPollingRunner(
    private val api: TelegramApiClient,
    private val sink: UpdateSink,
    private val scope: CoroutineScope,
    meterRegistry: MeterRegistry,
    private val timeoutSeconds: Int = 25,
    private val allowedUpdates: List<String> =
        listOf("message", "pre_checkout_query", "successful_payment"),
    private val logger: Logger = LoggerFactory.getLogger(LongPollingRunner::class.java),
) {
    private val metrics = LongPollingMetrics(meterRegistry)
    private val lifecycleLock = Any()

    @Volatile
    private var runnerJob: Job? = null

    init {
        require(timeoutSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            "timeoutSeconds must be between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS"
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun start(): Job {
        synchronized(lifecycleLock) {
            runnerJob?.let { return it }
            metrics.resetOffset()
            val job =
                scope.launch {
                    logger.info("Starting Telegram long polling runner")
                    try {
                        ensureWebhookDeleted()
                        runLoop()
                    } catch (cause: CancellationException) {
                        logger.info("Telegram long polling runner cancelled")
                        throw cause
                    } catch (cause: Exception) {
                        logger.error("Telegram long polling runner failed", cause)
                        throw cause
                    } finally {
                        logger.info("Telegram long polling runner stopped")
                    }
                }
            runnerJob = job
            job.invokeOnCompletion {
                metrics.resetOffset()
                synchronized(lifecycleLock) {
                    if (runnerJob === job) {
                        runnerJob = null
                    }
                }
            }
            return job
        }
    }

    suspend fun stop() {
        val job = synchronized(lifecycleLock) { runnerJob }
        if (job != null) {
            logger.info("Stopping Telegram long polling runner")
            job.cancelAndJoin()
        }
    }

    private suspend fun ensureWebhookDeleted() {
        logger.info("Removing webhook before starting long polling")
        withRetry {
            api.deleteWebhook(dropPending = false)
        }
        logger.info("Webhook removed, switching to long polling")
    }

    private suspend fun runLoop() {
        var offset: Long? = null
        while (coroutineContext.isActive) {
            metrics.markCycle()
            val batch = pollOnce(offset)
            if (batch.isEmpty()) {
                continue
            }
            val firstId = batch.first().update_id
            val lastId = batch.last().update_id
            val lastProcessedId = handleBatch(batch)
            val ackedOffset =
                lastProcessedId?.let { lastIdValue ->
                    if (lastIdValue == Long.MAX_VALUE) {
                        lastIdValue
                    } else {
                        lastIdValue + 1
                    }
                }
            if (ackedOffset != null) {
                offset = ackedOffset
                metrics.updateOffset(ackedOffset)
            }
            logger.info(
                "LP batch size={} first={} last={} offset→{}",
                batch.size,
                firstId,
                lastId,
                offset ?: "-",
            )
        }
    }

    private suspend fun pollOnce(offset: Long?): List<UpdateDto> {
        val updates =
            withRetry {
                metrics.markRequest()
                val sample = metrics.startRequestSample()
                try {
                    api.getUpdates(offset, timeoutSeconds, allowedUpdates)
                } finally {
                    metrics.stopRequestSample(sample)
                }
            }
        metrics.markResponse(updates.size)
        return updates
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun handleBatch(batch: List<UpdateDto>): Long? {
        var maxId: Long? = null
        for (update in batch) {
            try {
                sink.enqueue(update)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.error("LP enqueue failed: updateId={}", update.update_id, cause)
            }
            val currentId = update.update_id
            if (maxId == null || currentId > maxId) {
                maxId = currentId
            }
        }
        return maxId
    }

    private suspend fun <T> withRetry(
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        baseDelayMs: Long = INITIAL_BACKOFF_DELAY_MS,
        block: suspend () -> T,
    ): T = runWithRetry(maxAttempts, baseDelayMs, metrics, logger, block)
}

private class LongPollingMetrics(
    private val registry: MeterRegistry,
) {
    private val componentTag = MetricsTags.COMPONENT to LP_COMPONENT
    private val requestTimer = Metrics.timer(registry, MetricsNames.LP_REQUEST_SECONDS, componentTag)
    private val requestCounter = Metrics.counter(registry, MetricsNames.LP_REQUESTS_TOTAL, componentTag)
    private val responseCounter = Metrics.counter(registry, MetricsNames.LP_RESPONSES_TOTAL, componentTag)
    private val batchCounter = Metrics.counter(registry, MetricsNames.LP_BATCHES_TOTAL, componentTag)
    private val updatesCounter = Metrics.counter(registry, MetricsNames.LP_UPDATES_TOTAL, componentTag)
    private val errorsCounter = Metrics.counter(registry, MetricsNames.LP_ERRORS_total, componentTag)
    private val retriesCounter = Metrics.counter(registry, MetricsNames.LP_RETRIES_TOTAL, componentTag)
    private val cycleCounter = Metrics.counter(registry, MetricsNames.LP_CYCLES_TOTAL, componentTag)
    private val offsetGauge = AtomicLong(UNINITIALIZED_OFFSET)

    init {
        Metrics.gaugeLong(registry, MetricsNames.LP_OFFSET_CURRENT, offsetGauge, componentTag)
    }

    fun markRequest() {
        requestCounter.increment()
    }

    fun startRequestSample(): Timer.Sample = Timer.start(registry)

    fun stopRequestSample(sample: Timer.Sample) {
        sample.stop(requestTimer)
    }

    fun markResponse(count: Int) {
        responseCounter.increment()
        if (count > 0) {
            batchCounter.increment()
            updatesCounter.increment(count.toDouble())
        }
    }

    fun markError() {
        errorsCounter.increment()
    }

    fun markRetry() {
        retriesCounter.increment()
    }

    fun markCycle() {
        cycleCounter.increment()
    }

    fun updateOffset(value: Long) {
        offsetGauge.set(value)
    }

    fun resetOffset() {
        offsetGauge.set(UNINITIALIZED_OFFSET)
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> runWithRetry(
    maxAttempts: Int,
    baseDelayMs: Long,
    metrics: LongPollingMetrics,
    logger: Logger,
    block: suspend () -> T,
): T {
    var attempt = 1
    var currentDelay = baseDelayMs
    while (true) {
        try {
            return block()
        } catch (cause: CancellationException) {
            rethrowCancellation(cause)
        } catch (cause: ClientRequestException) {
            metrics.markError()
            logger.error(
                "LP request failed without retry: attempt={} status={}",
                attempt,
                cause.response.status.value,
                cause,
            )
            rethrow(cause)
        } catch (cause: Throwable) {
            val retriable = cause.isRetriableError()
            val hasAttemptsLeft = attempt < maxAttempts
            if (!retriable || !hasAttemptsLeft) {
                metrics.markError()
                logger.error(
                    "LP request failed without retry: attempt={} type={}",
                    attempt,
                    cause.javaClass.simpleName,
                    cause,
                )
                rethrow(cause)
            }
            metrics.markRetry()
            val delayMillis = nextDelayWithJitter(currentDelay)
            logger.warn(
                "LP request failed: attempt={} type={} retry in {} ms",
                attempt,
                cause.javaClass.simpleName,
                delayMillis,
                cause,
            )
            delay(delayMillis)
            attempt += 1
            currentDelay = (currentDelay * 2).coerceAtMost(MAX_BACKOFF_DELAY_MS)
        }
    }
}

private fun Throwable.isRetriableError(): Boolean =
    when (this) {
        is HttpRequestTimeoutException -> true
        is ConnectTimeoutException -> true
        is UnresolvedAddressException -> true
        is IOException -> true
        is ServerResponseException -> true
        else -> false
    }

private fun nextDelayWithJitter(delayMs: Long): Long {
    val jitter = Random.nextDouble(-JITTER_RATIO, JITTER_RATIO)
    val value = delayMs + (delayMs * jitter)
    val rounded = value.toLong()
    return if (rounded <= 0L) 1L else rounded
}

private fun rethrowCancellation(cause: CancellationException): Nothing = throw cause

private fun rethrow(cause: Throwable): Nothing = throw cause
