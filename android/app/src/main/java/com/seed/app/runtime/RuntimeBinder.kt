package com.seed.app.runtime

import android.os.Binder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

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
    private var supervisor: RuntimeSupervisor,
    val terminalManager: SeedTerminalManager,
    private val stopService: () -> Unit,
    val restoreState: StateFlow<RestoreState> = kotlinx.coroutines.flow.MutableStateFlow(RestoreState.Idle),
    private val restoreRuntime: () -> Unit = {},
    private val launchAllowed: () -> Boolean = { true },
) : Binder() {
    private val mutableHealth = kotlinx.coroutines.flow.MutableStateFlow(supervisor.health.value)
    private val mirrorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var mirrorJob: kotlinx.coroutines.Job? = null
    val health: StateFlow<HealthState> = mutableHealth
    init { replaceSupervisor(supervisor) }
    internal fun replaceSupervisor(replacement: RuntimeSupervisor) {
        supervisor = replacement
        mirrorJob?.cancel()
        mirrorJob = mirrorScope.launch { replacement.health.collect { mutableHealth.value = it } }
    }
    fun restore() = restoreRuntime()
    internal fun close() { mirrorScope.cancel() }
    val isRuntimeAlive: Boolean get() = supervisor.isRuntimeAlive

    fun retry() { if (launchAllowed()) supervisor.startOrRetry() }

    fun restart() { if (!launchAllowed()) return
        restartRuntimeWithFreshTerminal(
        closeTerminal = terminalManager::close,
        restartRuntime = supervisor::restart,
        )
    }

    fun stop() = stopService()
}
