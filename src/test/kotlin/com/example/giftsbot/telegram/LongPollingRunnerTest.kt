package com.example.giftsbot.telegram

import com.example.app.observability.MetricsNames
import com.example.app.observability.MetricsTags
import com.example.app.telegram.UpdateSink
import com.example.app.telegram.dto.UpdateDto
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LongPollingRunnerTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val defaultAllowedUpdates = TelegramApiClient.AllowedUpdates.DEFAULT

    @AfterAll
    fun tearDown() {
        meterRegistry.close()
    }

    @Test
    fun `start removes webhook before polling`() {
        runTest {
            val api = mockk<TelegramApiClient>()
            val sink = mockk<UpdateSink>(relaxUnitFun = true)
            val (runner, runnerScope) = createRunner(testScheduler, api, sink)

            val firstPollStarted = CompletableDeferred<Unit>()
            coEvery { api.deleteWebhook(false) } returns true
            coEvery { api.getUpdates(any(), any(), any()) }
                .coAnswers {
                    firstPollStarted.complete(Unit)
                    awaitCancellation()
                }

            val job = runner.start()
            firstPollStarted.await()

            coVerifyOrder {
                api.deleteWebhook(false)
                api.getUpdates(null, 25, defaultAllowedUpdates)
            }

            runner.stop()
            assertTrue(job.isCompleted)
            runnerScope.cancel()
        }
    }

    @Test
    fun `offset advances after batches`() {
        runTest {
            val api = mockk<TelegramApiClient>()
            val sink = mockk<UpdateSink>(relaxUnitFun = true)
            val (runner, runnerScope) = createRunner(testScheduler, api, sink)

            val thirdCallStarted = CompletableDeferred<Unit>()
            val firstBatch = listOf(update(10), update(11))
            val secondBatch = listOf(update(12))

            coEvery { api.deleteWebhook(false) } returns true
            var callIndex = 0
            coEvery { api.getUpdates(any(), any(), any()) }
                .coAnswers {
                    when (callIndex++) {
                        0 -> {
                            assertNull(firstArg())
                            assertEquals(25, secondArg())
                            assertEquals(defaultAllowedUpdates, thirdArg())
                            firstBatch
                        }

                        1 -> {
                            assertEquals(12L, firstArg())
                            assertEquals(25, secondArg())
                            assertEquals(defaultAllowedUpdates, thirdArg())
                            secondBatch
                        }

                        2 -> {
                            assertEquals(13L, firstArg())
                            assertEquals(25, secondArg())
                            assertEquals(defaultAllowedUpdates, thirdArg())
                            thirdCallStarted.complete(Unit)
                            awaitCancellation()
                        }

                        else -> error("Unexpected poll invocation")
                    }
                }

            val job = runner.start()
            thirdCallStarted.await()

            coVerifySequence {
                sink.enqueue(firstBatch[0])
                sink.enqueue(firstBatch[1])
                sink.enqueue(secondBatch[0])
            }

            runner.stop()
            assertTrue(job.isCompleted)
            runnerScope.cancel()
        }
    }

    @Test
    fun `retriable failures are retried and counted`() {
        runTest {
            val api = mockk<TelegramApiClient>()
            val sink = mockk<UpdateSink>(relaxUnitFun = true)
            val (runner, runnerScope) = createRunner(testScheduler, api, sink)

            val fourthAttemptStarted = CompletableDeferred<Unit>()
            coEvery { api.deleteWebhook(false) } returns true
            var attempts = 0
            coEvery { api.getUpdates(any(), any(), any()) }
                .coAnswers {
                    attempts += 1
                    when (attempts) {
                        1, 2, 3 -> throw IOException("boom $attempts")
                        4 -> {
                            fourthAttemptStarted.complete(Unit)
                            awaitCancellation()
                        }

                        else -> error("Unexpected attempt $attempts")
                    }
                }

            val retriesCounter = counter(MetricsNames.LP_RETRIES_TOTAL)
            val errorsCounter = counter(MetricsNames.LP_ERRORS_total)
            val retriesBefore = retriesCounter.count()
            val errorsBefore = errorsCounter.count()

            val job = runner.start()
            advanceUntilIdle()
            fourthAttemptStarted.await()

            assertEquals(retriesBefore + 3.0, retriesCounter.count())
            assertEquals(errorsBefore, errorsCounter.count())
            coVerify(exactly = 4) { api.getUpdates(any(), any(), any()) }

            runner.stop()
            assertTrue(job.isCompleted)
            runnerScope.cancel()
        }
    }

    @Test
    fun `non retriable failure increments error counter without retry`() {
        runTest {
            val api = mockk<TelegramApiClient>()
            val sink = mockk<UpdateSink>(relaxUnitFun = true)
            val (runner, runnerScope) = createRunner(testScheduler, api, sink)

            coEvery { api.deleteWebhook(false) } returns true
            val clientError = clientErrorException()
            coEvery { api.getUpdates(any(), any(), any()) } throws clientError

            val retriesCounter = counter(MetricsNames.LP_RETRIES_TOTAL)
            val errorsCounter = counter(MetricsNames.LP_ERRORS_total)
            val retriesBefore = retriesCounter.count()
            val errorsBefore = errorsCounter.count()

            val job = runner.start()
            advanceUntilIdle()

            assertTrue(job.isCancelled)
            assertEquals(retriesBefore, retriesCounter.count())
            assertEquals(errorsBefore + 1.0, errorsCounter.count())
            coVerify(exactly = 1) { api.getUpdates(any(), any(), any()) }

            runner.stop()
            runnerScope.cancel()
        }
    }

    @Test
    fun `stop cancels running job`() {
        runTest {
            val api = mockk<TelegramApiClient>()
            val sink = mockk<UpdateSink>(relaxUnitFun = true)
            val (runner, runnerScope) = createRunner(testScheduler, api, sink)

            val pollStarted = CompletableDeferred<Unit>()
            coEvery { api.deleteWebhook(false) } returns true
            coEvery { api.getUpdates(any(), any(), any()) }
                .coAnswers {
                    pollStarted.complete(Unit)
                    awaitCancellation()
                }

            val job = runner.start()
            pollStarted.await()

            runner.stop()

            assertTrue(job.isCompleted)
            coVerify(exactly = 1) { api.getUpdates(any(), any(), any()) }

            runnerScope.cancel()
        }
    }

    private fun createRunner(
        scheduler: TestCoroutineScheduler,
        api: TelegramApiClient,
        sink: UpdateSink,
    ): Pair<LongPollingRunner, TestScope> {
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = TestScope(SupervisorJob() + dispatcher)
        val runner =
            LongPollingRunner(
                api = api,
                sink = sink,
                scope = scope,
                meterRegistry = meterRegistry,
            )
        return runner to scope
    }

    private fun counter(name: String): Counter = meterRegistry.get(name).tag(MetricsTags.COMPONENT, "lp").counter()

    private fun update(id: Long): UpdateDto = UpdateDto(update_id = id)

    private fun clientErrorException(): ClientRequestException {
        val exception = mockk<ClientRequestException>()
        val response = mockk<HttpResponse>()
        every { exception.response } returns response
        every { response.status } returns HttpStatusCode.BadRequest
        return exception
    }
}
