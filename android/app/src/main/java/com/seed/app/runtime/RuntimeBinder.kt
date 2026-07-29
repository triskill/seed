package com.seed.app.runtime

import android.os.Binder
import kotlinx.coroutines.flow.StateFlow

/**
 * Bound-service surface consumed by the activity during runtime startup.
 *
 * The process PID is intentionally not exposed: Android's Process API does not
 * provide it on the project's API level. Callers only need liveness, health,
 * and an explicit way to stop the foreground service.
 */
class RuntimeBinder internal constructor(
    val health: StateFlow<HealthState>,
    private val runtimeIsAlive: () -> Boolean,
    private val stopService: () -> Unit,
) : Binder() {
    val isRuntimeAlive: Boolean get() = runtimeIsAlive()

    fun stop() = stopService()
}
