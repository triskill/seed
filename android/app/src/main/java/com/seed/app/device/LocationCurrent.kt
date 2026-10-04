package com.seed.app.device

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.round

internal data class LocationGrants(val coarse: Boolean, val fine: Boolean) {
    val available get() = coarse || fine
}
internal interface LocationPermission { fun grants(): LocationGrants }
internal data class LocationFix(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val timestampMs: Long, val elapsedRealtimeMs: Long)
internal interface LocationProvider {
    fun enabledProviders(): List<String>
    fun elapsedRealtimeMs(): Long
    /** Returns a removal action; callbacks and removal are owned by the calling dispatcher. */
    fun subscribe(provider: String, fix: (LocationFix) -> Unit, unavailable: () -> Unit): () -> Unit
}

/** Foreground one-shot policy. No Android dependency so permission/provider races are deterministic in tests. */
internal class LocationCurrent(private val permission: LocationPermission, private val provider: LocationProvider,
    private val foreground: LocationForeground = AlwaysLocationForeground,
    private val onOwnerThread: (() -> Unit) -> Unit = { it() },
) {
    var launchPermissions: (Array<String>) -> Unit = { throw DeviceCapabilityError("UNAVAILABLE", "Location permission launcher unavailable") }
    private val permissions = CameraResultSlot<Map<String, Boolean>>()
    private var closed = false
    private var operation: Job? = null
    fun isPermissionBusy() = permissions.isBusy()
    fun permissionResult(result: Map<String, Boolean>) { permissions.complete(result) }
    fun close() { closed = true; permissions.close(); operation?.cancel(); launchPermissions = {} }

    suspend fun current(params: Map<String, Any?>): Map<String, Any?> {
        val accuracy = if (params.containsKey("accuracy")) params["accuracy"] else "coarse"
        val timeout = if (params.containsKey("timeoutMs")) (params["timeoutMs"] as? Number)?.toDouble() else 15000.0
        if (params.keys.any { it !in setOf("accuracy", "timeoutMs") } || accuracy !in setOf("coarse", "fine") ||
            timeout == null || !timeout.isFinite() || timeout !in 1000.0..60000.0 || timeout % 1 != 0.0)
            throw DeviceCapabilityError("INVALID_PARAMS", "Invalid location parameters")
        if (closed) throw DeviceCapabilityError("CANCELLED", "Location access closed")
        if (!foreground.isVisible()) throw DeviceCapabilityError("UNAVAILABLE", "Location requires a visible app")
        if (operation != null || permissions.isBusy()) throw DeviceCapabilityError("BUSY", "Location operation in progress")
        operation = currentCoroutineContext()[Job]
        try {
            return withTimeout(timeout.toLong()) {
                if (!permission.grants().available) requestPermission(accuracy == "fine")
                // The permission result may arrive while the activity is still paused.
                foreground.awaitResumed()
                // Callback maps can be stale. Always re-read the actual OS permission.
                val grants = permission.grants()
                if (!grants.available) throw DeviceCapabilityError("PERMISSION_DENIED", "Location permission unavailable")
                val coarse = accuracy == "coarse" || !grants.fine
                val enabled = provider.enabledProviders()
                // GPS can provide the source of an approximate result when fine
                // platform access exists; output quantization still applies.
                val choices = if (coarse) listOf("network", "fused") + if (grants.fine) listOf("gps") else emptyList()
                    else listOf("gps", "fused", "network")
                val selected = choices.firstOrNull { it in enabled } ?: throw DeviceCapabilityError("UNAVAILABLE", "Location provider unavailable or disabled")
                var remove: (() -> Unit)? = null
                var removeStop: (() -> Unit)? = null
                try {
                    suspendCancellableCoroutine { continuation ->
                        removeStop = foreground.onStop {
                            if (continuation.isActive) continuation.resumeWithException(DeviceCapabilityError("CANCELLED", "App no longer visible"))
                        }
                        if (!foreground.isVisible()) {
                            continuation.resumeWithException(DeviceCapabilityError("CANCELLED", "App no longer visible"))
                            return@suspendCancellableCoroutine
                        }
                        remove = provider.subscribe(selected, { fix ->
                            val age = provider.elapsedRealtimeMs() - fix.elapsedRealtimeMs
                            if (continuation.isActive && age in 0..10000 && valid(fix)) {
                                val actual = permission.grants()
                                if (!actual.available) {
                                    continuation.resumeWithException(DeviceCapabilityError("PERMISSION_DENIED", "Location permission unavailable"))
                                    return@subscribe
                                }
                                val outputCoarse = coarse || !actual.fine
                                // A precise Android grant must never override the requested coarse precision.
                                continuation.resume(mapOf(
                                    "latitude" to if (outputCoarse) round(fix.latitude * 100) / 100 else fix.latitude,
                                    "longitude" to if (outputCoarse) round(fix.longitude * 100) / 100 else fix.longitude,
                                    "accuracyMeters" to if (outputCoarse) maxOf(1500.0, fix.accuracyMeters) else fix.accuracyMeters,
                                    "timestampMs" to fix.timestampMs, "precision" to if (outputCoarse) "coarse" else "fine", "ageMs" to age,
                                ))
                            }
                        }, {
                            if (continuation.isActive) continuation.resumeWithException(DeviceCapabilityError("UNAVAILABLE", "Location provider disabled"))
                        })
                    }
                } finally { removeStop?.invoke(); remove?.invoke() }
            }
        } catch (error: TimeoutCancellationException) {
            throw DeviceCapabilityError("TIMEOUT", "Location read timed out")
        } catch (error: SecurityException) {
            throw DeviceCapabilityError("PERMISSION_DENIED", "Location permission unavailable")
        } finally { operation = null }
    }

    private suspend fun requestPermission(fine: Boolean) {
        suspendCancellableCoroutine<Unit> { continuation ->
            if (!permissions.reserve { if (continuation.isActive) continuation.resume(Unit) }) {
                continuation.resumeWithException(DeviceCapabilityError("BUSY", "Location permission request in progress"))
            } else {
                val token = permissions.token()
                continuation.invokeOnCancellation { onOwnerThread { permissions.abandon(token) } }
                try {
                    launchPermissions(if (fine) arrayOf(COARSE, FINE) else arrayOf(COARSE))
                } catch (error: Exception) {
                    permissions.abandon(token)
                    permissions.complete(emptyMap())
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }
    private fun valid(fix: LocationFix) = fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
        fix.longitude.isFinite() && fix.longitude in -180.0..180.0 && fix.accuracyMeters.isFinite() && fix.accuracyMeters >= 0
    companion object {
        const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    }
}
