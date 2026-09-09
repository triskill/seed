package com.seed.app.runtime

import android.os.Binder
import kotlinx.coroutines.flow.StateFlow

/**
 * Bound-service surface consumed by the activity during runtime startup.
 *
 * The process PID is intentionally not exposed: Android's Process API does not
 * provide it on the project's API level. Callers only need liveness, health,
 * retry, and an explicit way to stop the foreground service.
 *
 * Properties:
 * - [health]: stateful health of the embedded backend process.
 * - [isRuntimeAlive]: whether the backend PRoot process is running.
 * - [terminalManager]: the interactive shell session manager (exposed
 *   for the Shell tab's composable to get a TerminalSession reference).
 *   The terminal session survives activity recreation and navigation
 *   changes — it is owned by the service.
 */
class RuntimeBinder internal constructor(
    private val supervisor: RuntimeSupervisor,
    val terminalManager: SeedTerminalManager,
    private val stopService: () -> Unit,
) : Binder() {
    val health: StateFlow<HealthState> = supervisor.health
    val isRuntimeAlive: Boolean get() = supervisor.isRuntimeAlive

    fun retry() = supervisor.startOrRetry()

    fun stop() = stopService()
}
