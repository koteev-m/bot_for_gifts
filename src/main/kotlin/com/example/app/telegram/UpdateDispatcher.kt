package com.example.app.telegram

import com.example.app.observability.Metrics
import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.telegram.dto.UpdateDto
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_QUEUE_CAPACITY = 10_000
private const val DEFAULT_DEDUP_TTL_HOURS = 26L
private const val CLEANUP_INTERVAL_MINUTES = 15L
private const val DEFAULT_CLOSE_TIMEOUT_SECONDS = 5L
private val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_CLOSE_TIMEOUT_SECONDS)

data class UpdateDispatcherSettings(
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    val dedupTtl: Duration = Duration.ofHours(DEFAULT_DEDUP_TTL_HOURS),
    val workers: Int = 1,
    val logger: Logger = LoggerFactory.getLogger(UpdateDispatcher::class.java),
)

class UpdateDispatcher(
    private val scope: CoroutineScope,
    private val meterRegistry: MeterRegistry,
    private val settings: UpdateDispatcherSettings = UpdateDispatcherSettings(),
    private val handleUpdate: suspend (IncomingUpdate) -> Unit = { _ -> },
) : UpdateSink {
    private val logger = settings.logger
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val queueSize = AtomicInteger(0)
    private val metrics = QueueMetrics(meterRegistry, queueSize)

    private val channel =
        Channel<IncomingUpdate>(
            capacity = settings.queueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { incoming ->
                decrementQueueGauge()
                metrics.markDropped()
                logger.debug("Undelivered update {} removed from queue", incoming.updateId)
            },
        )

    private val seen = ConcurrentHashMap<Long, Long>()
    private val workerJobs = mutableListOf<Job>()
    private var cleanupJob: Job? = null
    private val activeWorkers = AtomicInteger(0)

    @Volatile
    private var workersCompletion: CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }
    private val workerExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                logger.error("Worker crashed", throwable)
            }
        }
    private val cleanupExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                logger.error("Cleanup job crashed", throwable)
            }
        }

    private val cleanupInterval = Duration.ofMinutes(CLEANUP_INTERVAL_MINUTES)

    fun start(workers: Int = settings.workers.coerceAtLeast(1)) {
        if (closed.get()) {
            logger.warn("Dispatcher already closed")
            return
        }
        if (!started.compareAndSet(false, true)) {
            logger.warn("UpdateDispatcher is already started")
            return
        }

        val workerCount = workers.coerceAtLeast(1)
        activeWorkers.set(workerCount)
        workersCompletion = CompletableDeferred()
        repeat(workerCount) { index ->
            val job =
                scope.launch(workerExceptionHandler + Dispatchers.IO + CoroutineName("dispatcher-worker-$index")) {
                    try {
                        for (incoming in channel) {
                            decrementQueueGauge()
                            processIncoming(incoming)
                        }
                    } finally {
                        if (activeWorkers.decrementAndGet() == 0 && !workersCompletion.isCompleted) {
                            workersCompletion.complete(Unit)
                        }
                    }
                }
            workerJobs += job
        }

        cleanupJob =
            scope
                .launch(cleanupExceptionHandler + Dispatchers.IO + CoroutineName("dispatcher-cleanup")) {
                    while (isActive) {
                        delay(cleanupInterval.toMillis())
                        val threshold = System.currentTimeMillis() - settings.dedupTtl.toMillis()
                        seen.entries.removeIf { it.value < threshold }
                    }
                }.also { job ->
                    job.invokeOnCompletion { cause ->
                        if (cause is CancellationException) {
                            logger.debug("Cleanup job cancelled")
                        }
                    }
                }

        logger.info(
            "UpdateDispatcher started: workers={}, capacity={}, ttl={}",
            workerCount,
            settings.queueCapacity,
            settings.dedupTtl,
        )
    }

    override suspend fun enqueue(update: UpdateDto) {
        if (closed.get()) {
            metrics.markDropped()
            logger.warn("enqueue called after close; dropping update_id={}", update.update_id)
            return
        }

        if (markSeen(update.update_id)) {
            val incoming = update.toIncoming()
            val result = channel.trySend(incoming)
            if (result.isSuccess) {
                incrementQueueGauge()
                metrics.markEnqueued()
            } else {
                metrics.markDropped()
                if (result.isClosed) {
                    logger.warn("Queue is closed, dropping update {}", incoming.updateId)
                } else {
                    logger.warn("Queue overflow, dropping update {}", incoming.updateId)
                }
            }
        }
    }

    suspend fun close(timeout: Duration = DEFAULT_CLOSE_TIMEOUT) {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        channel.close()

        runBlocking {
            workersCompletion.await()
            cleanupJob?.cancelAndJoin()
        }

        logger.info(
            "UpdateDispatcher closed: workers joined, cleanup stopped (timeout={}ms)",
            timeout.toMillis(),
        )
    }

    private fun markSeen(updateId: Long): Boolean {
        val now = System.currentTimeMillis()
        val previous = seen.putIfAbsent(updateId, now)
        if (previous != null) {
            metrics.markDuplicate()
            return false
        }
        return true
    }

    private suspend fun processIncoming(incoming: IncomingUpdate) {
        val startNanos = System.nanoTime()
        val result =
            runCatching {
                logger.info(
                    "queue handled updateId={} type={}",
                    incoming.updateId,
                    incoming::class.simpleName,
                )
                handleUpdate(incoming)
                metrics.markProcessed()
            }
        result.exceptionOrNull()?.let { exception ->
            if (exception is CancellationException) {
                throw exception
            }
            logger.error("queue handling failed: updateId={}", incoming.updateId, exception)
        }
        val elapsedNanos = System.nanoTime() - startNanos
        metrics.recordHandleDuration(elapsedNanos)
    }

    private fun incrementQueueGauge() {
        queueSize.incrementAndGet()
    }

    private fun decrementQueueGauge() {
        queueSize.updateAndGet { current ->
            if (current > 0) current - 1 else 0
        }
    }
}

private class QueueMetrics(
    registry: MeterRegistry,
    queueSize: AtomicInteger,
) {
    private val componentTag = MetricsTags.COMPONENT to QUEUE_COMPONENT
    private val enqueuedCounter =
        Metrics.counter(registry, MetricsNames.UPDATES_ENQUEUED_TOTAL, componentTag)
    private val duplicateCounter =
        Metrics.counter(registry, MetricsNames.UPDATES_DUPLICATED_TOTAL, componentTag)
    private val droppedCounter =
        Metrics.counter(registry, MetricsNames.UPDATES_DROPPED_TOTAL, componentTag)
    private val processedCounter =
        Metrics.counter(registry, MetricsNames.UPDATES_PROCESSED_TOTAL, componentTag)
    private val handleTimer =
        Metrics.timer(registry, MetricsNames.UPDATE_HANDLE_SECONDS, componentTag)

    init {
        Metrics.gaugeInt(registry, MetricsNames.QUEUE_SIZE_GAUGE, queueSize, componentTag)
    }

    fun markEnqueued() {
        enqueuedCounter.increment()
    }

    fun markDuplicate() {
        duplicateCounter.increment()
    }

    fun markDropped() {
        droppedCounter.increment()
    }

    fun markProcessed() {
        processedCounter.increment()
    }

    fun recordHandleDuration(durationNanos: Long) {
        if (durationNanos >= 0) {
            handleTimer.record(durationNanos, TimeUnit.NANOSECONDS)
        }
    }
}

private const val QUEUE_COMPONENT = "queue"
