package com.example.giftsbot.telegram

import com.example.app.telegram.UpdateSink
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.micrometer.core.instrument.Counter
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

@Suppress("LongParameterList")
class LongPollingRunner(
    private val api: TelegramApiClient,
    private val sink: UpdateSink,
    private val scope: CoroutineScope,
    private val meterRegistry: MeterRegistry,
    private val timeoutSeconds: Int = 25,
    private val allowedUpdates: List<String> = listOf("message", "pre_checkout_query", "successful_payment"),
    private val logger: Logger = LoggerFactory.getLogger(LongPollingRunner::class.java),
) {
    private val requestTimer: Timer = meterRegistry.timer("lp_request_seconds")
    private val requestCounter: Counter = meterRegistry.counter("lp_requests_total")
    private val responseCounter: Counter = meterRegistry.counter("lp_responses_total")
    private val batchCounter: Counter = meterRegistry.counter("lp_batches_total")
    private val updateCounter: Counter = meterRegistry.counter("lp_updates_total")
    private val errorCounter: Counter = meterRegistry.counter("lp_errors_total")
    private val retryCounter: Counter = meterRegistry.counter("lp_retries_total")
    private val cycleCounter: Counter = meterRegistry.counter("lp_cycles_total")
    private val offsetGauge = AtomicLong(UNINITIALIZED_OFFSET)
    private val lifecycleLock = Any()

    @Volatile
    private var runnerJob: Job? = null

    init {
        require(timeoutSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            "timeoutSeconds must be between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS"
        }
        meterRegistry.gauge("lp_offset_current", offsetGauge)
    }

    @Suppress("TooGenericExceptionCaught")
    fun start(): Job {
        synchronized(lifecycleLock) {
            runnerJob?.let { return it }
            offsetGauge.set(UNINITIALIZED_OFFSET)
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
                offsetGauge.set(UNINITIALIZED_OFFSET)
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
            cycleCounter.increment()
            val batch = pollOnce(offset)
            if (batch.isEmpty()) {
                continue
            }
            val firstId = batch.first().update_id
            val lastId = batch.last().update_id
            logger.info("Received {} updates ({}..{})", batch.size, firstId, lastId)
            val lastProcessedId = handleBatch(batch)
            if (lastProcessedId != null) {
                val nextOffset = if (lastProcessedId == Long.MAX_VALUE) lastProcessedId else lastProcessedId + 1
                offset = nextOffset
                offsetGauge.set(nextOffset)
                logger.info("Updated offset to {} after update {}", nextOffset, lastProcessedId)
            }
        }
    }

    private suspend fun pollOnce(offset: Long?): List<UpdateDto> {
        val updates =
            withRetry {
                requestCounter.increment()
                val sample = Timer.start(meterRegistry)
                try {
                    api.getUpdates(offset, timeoutSeconds, allowedUpdates)
                } finally {
                    sample.stop(requestTimer)
                }
            }
        responseCounter.increment()
        if (updates.isNotEmpty()) {
            batchCounter.increment()
            updateCounter.increment(updates.size.toDouble())
        }
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
                logger.error("Failed to enqueue update {}", update.update_id, cause)
            }
            val currentId = update.update_id
            if (maxId == null || currentId > maxId) {
                maxId = currentId
            }
        }
        return maxId
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> withRetry(
        maxAttempts: Int = 4,
        baseDelayMs: Long = INITIAL_BACKOFF_DELAY_MS,
        block: suspend () -> T,
    ): T {
        var attempt = 1
        var currentDelay = baseDelayMs
        while (true) {
            try {
                return block()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: ClientRequestException) {
                errorCounter.increment()
                logger.error(
                    "Long polling client error on attempt {}: status={}",
                    attempt,
                    cause.response.status.value,
                    cause,
                )
                rethrow(cause)
            } catch (cause: Throwable) {
                val retriable = isRetriable(cause)
                val hasAttemptsLeft = attempt < maxAttempts
                if (!retriable || !hasAttemptsLeft) {
                    errorCounter.increment()
                    logger.error(
                        "Long polling operation failed on attempt {}: type={} message={} без ретрая",
                        attempt,
                        cause.javaClass.simpleName,
                        cause.message,
                        cause,
                    )
                    rethrow(cause)
                }
                retryCounter.increment()
                val delayMillis = nextDelayWithJitter(currentDelay)
                logger.warn(
                    "Long polling operation failed on attempt {}: type={} будет ретрай через {} ms",
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

    private fun nextDelayWithJitter(delayMs: Long): Long {
        val jitter = Random.nextDouble(-JITTER_RATIO, JITTER_RATIO)
        val value = delayMs + (delayMs * jitter)
        val rounded = value.toLong()
        return if (rounded <= 0L) 1L else rounded
    }

    private fun isRetriable(cause: Throwable): Boolean =
        when (cause) {
            is HttpRequestTimeoutException -> true
            is ConnectTimeoutException -> true
            is UnresolvedAddressException -> true
            is IOException -> true
            is ServerResponseException -> true
            else -> false
        }

    private fun rethrow(cause: Throwable): Nothing = throw cause
}
