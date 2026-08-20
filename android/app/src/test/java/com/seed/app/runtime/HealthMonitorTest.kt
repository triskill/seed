package com.seed.app.runtime

import com.seed.app.data.BackendApi
import com.seed.app.data.ConfigRequest
import com.seed.app.data.ConfigResponse
import com.seed.app.data.HealthResponse
import com.seed.app.data.ShellExecRequest
import com.seed.app.data.ShellExecResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthMonitorTest {

    @Test
    fun successfulProbeEmitsHealthyAndStopsPolling() = runTest {
        val api = FakeBackendApi(
            responses = ArrayDeque(
                listOf(Result.success(HealthResponse(status = "ok", flask = "up"))),
            ),
        )

        val states = HealthMonitor(api, intervalMs = 500, maxAttempts = 60)
            .states()
            .toList()

        assertEquals(
            listOf(
                HealthState.Unknown,
                HealthState.Polling(attempt = 1),
                HealthState.Healthy(flask = "up"),
            ),
            states,
        )
        assertEquals(1, api.healthCalls)
    }

    @Test
    fun flaskDownIsRetriedUntilTheAppIsReady() = runTest {
        val api = FakeBackendApi(
            responses = ArrayDeque(
                listOf(
                    Result.success(HealthResponse(status = "ok", flask = "down")),
                    Result.success(HealthResponse(status = "ok", flask = "up")),
                ),
            ),
        )

        val states = HealthMonitor(
            api = api,
            intervalMs = 500,
            maxAttempts = 2,
            nowMs = { testScheduler.currentTime },
        ).states().toList()

        assertEquals(
            listOf(
                HealthState.Unknown,
                HealthState.Polling(attempt = 1),
                HealthState.Polling(attempt = 2),
                HealthState.Healthy(flask = "up"),
            ),
            states,
        )
        assertEquals(2, api.healthCalls)
        assertEquals(500, testScheduler.currentTime)
    }

    @Test
    fun flaskDownAfterFinalAttemptIsUnhealthy() = runTest {
        val api = FakeBackendApi(
            responses = ArrayDeque(
                listOf(Result.success(HealthResponse(status = "ok", flask = "down"))),
            ),
        )

        val states = HealthMonitor(api, maxAttempts = 1).states().toList()

        assertEquals(
            HealthState.Unhealthy(message = "Flask app is not ready: down"),
            states.last(),
        )
        assertEquals(1, api.healthCalls)
    }

    @Test
    fun failedProbeIsRetriedAfterPollingInterval() = runTest {
        val api = FakeBackendApi(
            responses = ArrayDeque(
                listOf(
                    Result.failure(IllegalStateException("not ready")),
                    Result.success(HealthResponse(status = "ok", flask = "up")),
                ),
            ),
        )

        val states = HealthMonitor(
            api = api,
            intervalMs = 500,
            maxAttempts = 3,
            nowMs = { testScheduler.currentTime },
        ).states().toList()

        assertEquals(
            listOf(
                HealthState.Unknown,
                HealthState.Polling(attempt = 1),
                HealthState.Polling(attempt = 2),
                HealthState.Healthy(flask = "up"),
            ),
            states,
        )
        assertEquals(2, api.healthCalls)
        assertEquals(500, testScheduler.currentTime)
    }

    @Test
    fun slowFailureStillStartsNextProbeOnThePollingInterval() = runTest {
        val api = object : StubBackendApi() {
            var calls = 0

            override suspend fun health(): HealthResponse {
                calls += 1
                if (calls == 1) {
                    delay(400)
                    error("not ready")
                }
                return HealthResponse(status = "ok", flask = "up")
            }
        }

        val states = HealthMonitor(
            api = api,
            intervalMs = 500,
            maxAttempts = 2,
            nowMs = { testScheduler.currentTime },
        ).states().toList()

        assertEquals(HealthState.Healthy(flask = "up"), states.last())
        assertEquals(500, testScheduler.currentTime)
    }

    @Test
    fun exhaustedAttemptsEmitLastFailureAsUnhealthy() = runTest {
        val api = FakeBackendApi(
            responses = ArrayDeque(
                listOf(
                    Result.failure(IllegalStateException("starting")),
                    Result.failure(IllegalStateException("connection refused")),
                ),
            ),
        )

        val states = HealthMonitor(api, intervalMs = 500, maxAttempts = 2)
            .states()
            .toList()

        assertEquals(
            listOf(
                HealthState.Unknown,
                HealthState.Polling(attempt = 1),
                HealthState.Polling(attempt = 2),
                HealthState.Unhealthy(message = "connection refused"),
            ),
            states,
        )
        assertEquals(2, api.healthCalls)
    }

    @Test
    fun cancellationDuringProbePropagatesToTheBackendCall() = runTest {
        var backendCallCancelled = false
        val api = object : StubBackendApi() {
            override suspend fun health(): HealthResponse = try {
                awaitCancellation()
            } finally {
                backendCallCancelled = true
            }
        }

        val job = launch {
            HealthMonitor(api).states().toList()
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(backendCallCancelled)
    }

    @Test
    fun cancellationDuringRetryDelayStopsFurtherAttempts() = runTest {
        val api = FakeBackendApi(
            responses = ArrayDeque(
                listOf(
                    Result.failure(IllegalStateException("not ready")),
                    Result.success(HealthResponse(status = "ok", flask = "up")),
                ),
            ),
        )

        val job = launch {
            HealthMonitor(api, intervalMs = 500).states().toList()
        }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(1, api.healthCalls)
    }

    @Test
    fun hangingProbeTimesOutAndEmitsUnhealthy() = runTest {
        val api = object : StubBackendApi() {
            override suspend fun health(): HealthResponse {
                delay(Long.MAX_VALUE)
                error("unreachable")
            }
        }

        val states = HealthMonitor(api, intervalMs = 500, maxAttempts = 1)
            .states()
            .toList()

        assertEquals(
            listOf(
                HealthState.Unknown,
                HealthState.Polling(attempt = 1),
                HealthState.Unhealthy(message = "Health check timed out"),
            ),
            states,
        )
        assertEquals(500, testScheduler.currentTime)
    }
}

private class FakeBackendApi(
    private val responses: ArrayDeque<Result<HealthResponse>>,
) : StubBackendApi() {
    var healthCalls: Int = 0
        private set

    override suspend fun health(): HealthResponse {
        healthCalls += 1
        return responses.removeFirst().getOrThrow()
    }
}

private abstract class StubBackendApi : BackendApi {
    override suspend fun health(): HealthResponse = error("Not configured")

    override suspend fun shellExec(request: ShellExecRequest): ShellExecResponse =
        error("Not used by HealthMonitor")

    override suspend fun putConfig(payload: ConfigRequest): ConfigResponse =
        error("Not used by HealthMonitor")
}
