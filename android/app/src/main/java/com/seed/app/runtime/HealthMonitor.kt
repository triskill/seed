package com.seed.app.runtime

import com.seed.app.data.BackendApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/** The embedded backend's startup health as observed over HTTP. */
sealed class HealthState {
    data object Unknown : HealthState()
    data class Polling(val attempt: Int) : HealthState()
    data class Healthy(val flask: String) : HealthState()
    data class Unhealthy(val message: String) : HealthState()
}

/**
 * Polls the embedded backend until it responds or the attempt budget is exhausted.
 *
 * The runtime is ready only when `/health` responds and its `flask` field is `"up"`.
 * A reachable backend can report `"down"` briefly while the embedded app is still
 * starting, so that response is retried just like a failed request. Each request is
 * bounded by [intervalMs], and unsuccessful probes retry on that fixed start-to-start
 * cadence. The returned flow is cold, so each collector starts a fresh probe run.
 */
class HealthMonitor(
    private val api: BackendApi,
    private val intervalMs: Long = 500,
    private val maxAttempts: Int = 60,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun states(): Flow<HealthState> = flow {
        emit(HealthState.Unknown)

        for (attempt in 1..maxAttempts) {
            emit(HealthState.Polling(attempt))
            val startedAt = nowMs()

            val response = try {
                withTimeout(intervalMs) { api.health() }
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

    private suspend fun delayUntilNextProbe(startedAt: Long) {
        val elapsed = (nowMs() - startedAt).coerceAtLeast(0)
        val remaining = intervalMs - elapsed
        if (remaining > 0) delay(remaining)
    }

    private companion object {
        const val FLASK_READY_STATUS = "up"
    }
}
