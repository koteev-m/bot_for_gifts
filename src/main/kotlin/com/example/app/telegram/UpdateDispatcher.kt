package com.example.app.telegram

import com.example.app.telegram.dto.UpdateDto
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_QUEUE_CAPACITY = 10_000
private const val DEFAULT_DEDUP_TTL_HOURS = 26L
private const val CLEANUP_INTERVAL_MINUTES = 15L

class UpdateDispatcher(
    private val scope: CoroutineScope,
    private val meterRegistry: MeterRegistry,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val dedupTtl: Duration = Duration.ofHours(DEFAULT_DEDUP_TTL_HOURS),
    private val workers: Int = 1,
    private val logger: Logger = LoggerFactory.getLogger(UpdateDispatcher::class.java),
) : UpdateSink {
    private val seenUpdates = ConcurrentHashMap<Long, Long>()
    private val queueSize = AtomicInteger(0)
    private val channel =
        Channel<IncomingUpdate>(
            capacity = queueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = {
                queueSize.updateAndGet { current ->
                    if (current > 0) current - 1 else 0
                }
                logger.debug("Undelivered update {} removed from queue", it.updateId)
            },
        )
    private val enqueuedCounter = meterRegistry.counter("telegram_updates_enqueued_total")
    private val duplicateCounter = meterRegistry.counter("telegram_updates_duplicated_total")
    private val droppedCounter = meterRegistry.counter("telegram_updates_dropped_total")
    private val processedCounter = meterRegistry.counter("telegram_updates_processed_total")
    private val handleTimer: Timer = meterRegistry.timer("telegram_update_handle_seconds")
    private val lifecycleLock = Any()
    private val workerJobs = mutableListOf<Job>()
    private var cleanupJob: Job? = null

    @Volatile
    private var isStarted = false

    @Volatile
    private var isClosed = false

    private val cleanupInterval = Duration.ofMinutes(CLEANUP_INTERVAL_MINUTES)

    init {
        meterRegistry.gauge("telegram_queue_size", queueSize)
    }

    override suspend fun enqueue(update: UpdateDto) {
        if (isClosed) {
            droppedCounter.increment()
            logger.warn("Dispatcher is closed, dropping update {}", update.update_id)
            return
        }

        if (markSeen(update.update_id)) {
            deliverToChannel(update.toIncoming())
        }
    }

    fun start(workers: Int = this.workers) {
        synchronized(lifecycleLock) {
            check(!isClosed) { "Dispatcher already closed" }
            if (isStarted) {
                logger.warn("UpdateDispatcher is already started")
                return
            }
            isStarted = true
            val workerCount = workers.coerceAtLeast(1)
            repeat(workerCount) { workerIndex ->
                workerJobs += startWorker(workerIndex)
            }
            cleanupJob =
                scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(cleanupInterval.toMillis())
                        val threshold = System.currentTimeMillis() - dedupTtl.toMillis()
                        seenUpdates.entries.removeIf { it.value < threshold }
                    }
                }
        }
    }

    suspend fun close() {
        synchronized(lifecycleLock) {
            if (isClosed) {
                return
            }
            isClosed = true
        }
        channel.close()
        cleanupJob?.cancelAndJoin()
        workerJobs.joinAll()
    }

    private fun markSeen(updateId: Long): Boolean {
        val now = System.currentTimeMillis()
        val previous = seenUpdates.putIfAbsent(updateId, now)
        if (previous != null) {
            duplicateCounter.increment()
            return false
        }
        return true
    }

    private fun deliverToChannel(incoming: IncomingUpdate) {
        val result = channel.trySend(incoming)
        if (!result.isSuccess) {
            handleFailedEnqueue(incoming, result)
            return
        }
        queueSize.incrementAndGet()
        enqueuedCounter.increment()
    }

    private fun handleFailedEnqueue(
        incoming: IncomingUpdate,
        result: ChannelResult<Unit>,
    ) {
        droppedCounter.increment()
        if (result.isClosed) {
            logger.warn("Queue is closed, dropping update {}", incoming.updateId)
        } else {
            logger.warn("Queue overflow, dropping update {}", incoming.updateId)
        }
    }

    private fun startWorker(index: Int): Job =
        scope.launch(Dispatchers.IO) {
            for (incoming in channel) {
                queueSize.updateAndGet { current ->
                    if (current > 0) current - 1 else 0
                }
                processIncoming(incoming)
            }
            logger.info("Worker {} stopped", index)
        }

    private suspend fun processIncoming(incoming: IncomingUpdate) {
        val startNanos = System.nanoTime()
        try {
            val outcome = runCatching { handle(incoming) }
            outcome.onSuccess { processedCounter.increment() }
            outcome.exceptionOrNull()?.let { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                logger.error("Failed to handle update {}", incoming.updateId, throwable)
            }
        } finally {
            val elapsedNanos = System.nanoTime() - startNanos
            handleTimer.record(elapsedNanos, TimeUnit.NANOSECONDS)
        }
    }

    private suspend fun handle(incoming: IncomingUpdate) {
        logger.info("Processed {} with id {}", incoming::class.simpleName, incoming.updateId)
    }
}
