package com.seed.app.runtime

import android.os.Binder
import kotlinx.coroutines.flow.StateFlow

/**
 * Bound-service surface consumed by the activity during runtime startup.
 *
 * The process PID is intentionally not exposed: Android's Process API does not
 * provide it on the project's API level. Callers only need liveness, health,
 * retry, and an explicit way to stop the foreground service.
 */
class RuntimeBinder internal constructor(
    private val supervisor: RuntimeSupervisor,
    private val stopService: () -> Unit,
) : Binder() {
    val health: StateFlow<HealthState> = supervisor.health
    val isRuntimeAlive: Boolean get() = supervisor.isRuntimeAlive

    fun retry() = supervisor.startOrRetry()

    fun stop() = stopService()
}
