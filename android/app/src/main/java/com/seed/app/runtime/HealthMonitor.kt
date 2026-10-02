package com.seed.app.runtime

import com.seed.app.data.BackendApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/** The embedded backend's readiness as observed over HTTP. */
sealed class HealthState {
    data object Unknown : HealthState()
    data class Polling(val attempt: Int) : HealthState()
    data class Healthy(val flask: String) : HealthState()
    data class Unhealthy(val message: String) : HealthState()
}

/**
 * Polls startup readiness until success or exhaustion; [continuousStates] then
 * keeps checking readiness with a consecutive-failure threshold.
 *
 * The runtime is ready only when `/health` responds and its `flask` field is `"up"`.
 * A reachable backend can report `"down"` briefly while the embedded app is still
 * starting, so that response is retried just like a failed request. Each request has a
 * 2-second timeout, exceeding FlaskManager's 1.5-second `/api/ping` deadline to allow
 * for FastAPI overhead. Unsuccessful probes retry no sooner than [intervalMs] (500 ms
 * by default) after the previous probe started; slow requests do not overlap.
 * The default [maxAttempts] budget is 240 probes (about 120 seconds at the
 * default cadence for fast failures), leaving room for Flask startup and
 * sequential Pi readiness probes. The flow is cold, so each collector starts fresh.
 */
class HealthMonitor(
    private val api: BackendApi,
    private val intervalMs: Long = 500,
    private val maxAttempts: Int = 240,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /**
     * Preserve startup gating, then check readiness every five seconds without
     * emitting Polling/Unknown into the running UI. Three consecutive failures
     * require manual Retry; a successful probe resets the failure count.
     */
    fun continuousStates(): Flow<HealthState> = flow {
        var ready = false
        states().collect { state ->
            emit(state)
            ready = state is HealthState.Healthy
        }
        if (!ready) return@flow

        var failures = 0
        var previousProbeStarted = nowMs()
        while (true) {
            delayUntilNextProbe(previousProbeStarted, READINESS_INTERVAL_MS)
            previousProbeStarted = nowMs()
            val healthy = try {
                withTimeout(HEALTH_REQUEST_TIMEOUT_MS) {
                    api.health().flask == FLASK_READY_STATUS
                }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            failures = if (healthy) 0 else failures + 1
            if (failures >= READINESS_FAILURE_THRESHOLD) {
                emit(HealthState.Unhealthy("Runtime health check failed repeatedly"))
                return@flow
            }
        }
    }

    fun states(): Flow<HealthState> = flow {
        emit(HealthState.Unknown)

        for (attempt in 1..maxAttempts) {
            emit(HealthState.Polling(attempt))
            val startedAt = nowMs()

            val response = try {
                withTimeout(HEALTH_REQUEST_TIMEOUT_MS) { api.health() }
            } catch (_: TimeoutCancellationException) {
                if (attempt == maxAttempts) {
                    emit(HealthState.Unhealthy("Health check timed out"))
                    return@flow
                }
                delayUntilNextProbe(startedAt)
                continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (attempt == maxAttempts) {
                    emit(
                        HealthState.Unhealthy(
                            failure.message ?: "Health check failed",
                        ),
                    )
                    return@flow
                }
                delayUntilNextProbe(startedAt)
                continue
            }

            if (response.flask != FLASK_READY_STATUS) {
                if (attempt == maxAttempts) {
                    emit(
                        HealthState.Unhealthy(
                            "Flask app is not ready: ${response.flask}",
                        ),
                    )
                    return@flow
                }
                delayUntilNextProbe(startedAt)
                continue
            }

            emit(HealthState.Healthy(response.flask))
            return@flow
        }
    }

    private suspend fun delayUntilNextProbe(startedAt: Long, cadenceMs: Long = intervalMs) {
        val elapsed = (nowMs() - startedAt).coerceAtLeast(0)
        val remaining = cadenceMs - elapsed
        if (remaining > 0) delay(remaining)
    }

    private companion object {
        const val READINESS_INTERVAL_MS = 5_000L
        const val READINESS_FAILURE_THRESHOLD = 3
        const val FLASK_READY_STATUS = "up"
        const val HEALTH_REQUEST_TIMEOUT_MS = 2_000L
    }
}
