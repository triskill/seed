package cz.trety.seed.runtime

import cz.trety.seed.data.AgentApplyRequest
import cz.trety.seed.data.AgentApplyResponse
import cz.trety.seed.data.BackendApi
import cz.trety.seed.data.ModelsResponse
import cz.trety.seed.data.ProviderModelsRequest
import cz.trety.seed.data.ThinkingLevelsResponse
import cz.trety.seed.data.SelectionRequest
import cz.trety.seed.data.SelectionResponse
import cz.trety.seed.data.HealthResponse
import cz.trety.seed.data.ShellExecRequest
import cz.trety.seed.data.ShellExecResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthMonitorTest {

    @Test
    fun continuousMonitoringReportsThreeFailuresWithoutPollingUi() = runTest {
        var calls = 0
        val api = object : StubBackendApi() {
            override suspend fun health(): HealthResponse {
                calls++
                if (calls > 1) error("unavailable")
                return HealthResponse(status = "ok", flask = "up")
            }
        }
        val states = mutableListOf<HealthState>()
        val job = backgroundScope.launch {
            HealthMonitor(api, nowMs = { testScheduler.currentTime }).continuousStates().toList(states)
        }
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(HealthState.Healthy("up"), states.last())
        assertEquals(3, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(HealthState.Unhealthy("Runtime health check failed repeatedly"), states.last())
        assertEquals(4, calls)
        assertEquals(1, states.filterIsInstance<HealthState.Polling>().size)
        assertTrue(job.isCompleted)
    }

    @Test
    fun healthyProbeResetsConsecutiveFailures() = runTest {
        var calls = 0
        val api = object : StubBackendApi() {
            override suspend fun health(): HealthResponse {
                calls++
                return HealthResponse(status = "ok", flask = if (calls == 1 || calls == 4) "up" else "down")
            }
        }
        val states = mutableListOf<HealthState>()
        val job = backgroundScope.launch {
            HealthMonitor(api, nowMs = { testScheduler.currentTime }).continuousStates().toList(states)
        }
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(HealthState.Healthy("up"), states.last())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(HealthState.Unhealthy("Runtime health check failed repeatedly"), states.last())
        assertEquals(7, calls)
        assertTrue(job.isCompleted)
    }

    @Test
    fun failedStartupDoesNotEnterContinuousMonitoring() = runTest {
        val api = object : StubBackendApi() {
            var calls = 0
            override suspend fun health(): HealthResponse { calls++; error("offline") }
        }
        val states = HealthMonitor(api, maxAttempts = 1).continuousStates().toList()
        assertEquals(1, api.calls)
        assertEquals(HealthState.Unhealthy("offline"), states.last())
    }

    @Test
    fun continuousProbeTimeoutCountsAsFailureAndDoesNotOverlap() = runTest {
        var calls = 0
        var active = 0
        val api = object : StubBackendApi() {
            override suspend fun health(): HealthResponse {
                calls++
                if (calls == 1) return HealthResponse(status = "ok", flask = "up")
                active++
                assertEquals(1, active)
                try { awaitCancellation() } finally { active-- }
            }
        }
        val states = mutableListOf<HealthState>()
        backgroundScope.launch { HealthMonitor(api, nowMs = { testScheduler.currentTime }).continuousStates().toList(states) }
        runCurrent()
        advanceTimeBy(17_000)
        runCurrent()
        assertEquals(4, calls)
        assertEquals(0, active)
        assertEquals(HealthState.Unhealthy("Runtime health check failed repeatedly"), states.last())
    }

    @Test
    fun cancellationDuringContinuousProbeDoesNotPublishFailure() = runTest {
        var calls = 0
        var cancelled = false
        val api = object : StubBackendApi() {
            override suspend fun health(): HealthResponse {
                calls++
                if (calls == 1) return HealthResponse(status = "ok", flask = "up")
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        val states = mutableListOf<HealthState>()
        val job = backgroundScope.launch {
            HealthMonitor(api, nowMs = { testScheduler.currentTime }).continuousStates().toList(states)
        }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(HealthState.Healthy("up"), states.last())
    }

    @Test
    fun successfulProbeEmitsHealthyAndStopsPolling() = runTest {
        val api = FakeBackendApi(
            responses = ArrayDeque(
                listOf(Result.success(HealthResponse(status = "ok", flask = "up"))),
            ),
        )

        val states = HealthMonitor(api, intervalMs = 500)
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
    fun defaultBudgetAllowsLateReadinessAfterSixtyAttempts() = runTest {
        val api = object : StubBackendApi() {
            var calls = 0

            override suspend fun health(): HealthResponse {
                calls += 1
                return HealthResponse(
                    status = "ok",
                    flask = if (calls == 151) "up" else "down",
                )
            }
        }

        val states = HealthMonitor(api, nowMs = { testScheduler.currentTime })
            .states()
            .toList()

        assertEquals(151, api.calls)
        assertEquals(HealthState.Polling(attempt = 151), states[151])
        assertEquals(HealthState.Healthy(flask = "up"), states.last())
        assertEquals(75_000, testScheduler.currentTime)
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
    fun probeCompletingAfterPollingIntervalButWithinBackendBudgetIsHealthy() = runTest {
        val api = object : StubBackendApi() {
            var calls = 0

            override suspend fun health(): HealthResponse {
                calls += 1
                delay(1_600)
                return HealthResponse(status = "ok", flask = "up")
            }
        }

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
                HealthState.Healthy(flask = "up"),
            ),
            states,
        )
        assertEquals(1, api.calls)
        assertEquals(1_600, testScheduler.currentTime)
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
        assertEquals(2_000, testScheduler.currentTime)
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

    override suspend fun shellExec(request: ShellExecRequest, authorization: String): ShellExecResponse =
        error("Not used by HealthMonitor")
    override suspend fun updateModels(authorization: String): cz.trety.seed.data.ModelsUpdateResponse = error("Not used")
    override suspend fun models(provider: String, authorization: String): ModelsResponse = error("Not used")
    override suspend fun config(authorization: String): cz.trety.seed.data.PiConfigResponse = error("Not used")
    override suspend fun addProvider(request: ProviderModelsRequest, authorization: String): cz.trety.seed.data.PiConfigResponse = error("Not used")
    override suspend fun thinkingLevels(provider: String, modelId: String, authorization: String): ThinkingLevelsResponse = error("Not used")
    override suspend fun validateSelection(request: SelectionRequest, authorization: String): SelectionResponse = error("Not used")
    override suspend fun applyAgents(request: AgentApplyRequest, authorization: String): AgentApplyResponse = error("Not used")

}
