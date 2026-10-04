package com.seed.app.runtime

/** Boot failure must not hide the service-owned restore coordinator on recreation. */
fun shouldBindMaintenance(@Suppress("UNUSED_PARAMETER") boot: BootState, maintenanceActive: Boolean) = maintenanceActive

fun rootScreensAllowed(state: RestoreState, maintenanceActive: Boolean): Boolean =
    !maintenanceActive && state !is RestoreState.Working && state !is RestoreState.Failed
