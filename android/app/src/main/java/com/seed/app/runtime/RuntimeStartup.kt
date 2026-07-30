package com.seed.app.runtime

internal sealed interface StartupDestination {
    data class Extraction(val state: BootState) : StartupDestination
    data class Runtime(val health: HealthState) : StartupDestination
    data object Seed : StartupDestination
}

internal fun resolveStartupDestination(
    boot: BootState,
    health: HealthState,
): StartupDestination = when (boot) {
    BootState.NeedsExtraction,
    is BootState.Extracting,
    -> StartupDestination.Extraction(boot)

    BootState.Ready -> when (health) {
        is HealthState.Healthy -> StartupDestination.Seed
        HealthState.Unknown,
        is HealthState.Polling,
        is HealthState.Unhealthy,
        -> StartupDestination.Runtime(health)
    }
}

internal class RuntimeStartupGate(
    private val onReady: () -> Unit,
) {
    private var readyHandled = false

    fun update(boot: BootState) {
        if (boot is BootState.Ready && !readyHandled) {
            readyHandled = true
            onReady()
        }
    }
}

internal fun shouldRequestRuntimeNotificationPermission(
    sdkInt: Int,
    granted: Boolean,
    alreadyRequested: Boolean,
): Boolean = sdkInt >= 33 && !granted && !alreadyRequested
