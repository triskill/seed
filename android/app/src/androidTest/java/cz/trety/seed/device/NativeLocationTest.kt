package cz.trety.seed.device

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** All GPS and permissions are fake. Never asks Android for real location or changes OS permissions. */
class NativeLocationTest {
    private class Permission(var coarse: Boolean = false, var fine: Boolean = false) : LocationPermission {
        override fun grants() = LocationGrants(coarse, fine)
    }
    private class Provider : LocationProvider {
        var enabled = listOf("network", "gps")
        var callback: ((LocationFix) -> Unit)? = null
        var removed = 0
        override fun enabledProviders() = enabled
        override fun elapsedRealtimeMs() = 20000L
        override fun subscribe(provider: String, fix: (LocationFix) -> Unit, unavailable: () -> Unit): () -> Unit {
            callback = fix
            return { removed++ }
        }
        fun emit(age: Long = 123) { callback!!(LocationFix(51.123456, -0.123456, 2.0, 123456L, 20000 - age)) }
    }
    private fun host(permission: Permission, provider: Provider, store: MemoryDeviceConsentStore = MemoryDeviceConsentStore()) =
        AndroidDeviceCapabilities(InstrumentationRegistry.getInstrumentation().targetContext, store,
            locationPermission = permission, locationProvider = provider)

    @Test fun fakeSuccessNeedsNativeConsentAndCoarseAlwaysQuantizes() = runBlocking {
        withContext(Dispatchers.Main) {
            val provider = Provider()
            val host = host(Permission(true, true), provider)
            try {
                host.launchLocationPermissions = { fail("Existing OS permission suffices") }
                val call = async { host.invoke("location.current", emptyMap()) }
                yield(); assertEquals("location.current", host.confirmation)
                assertNull(provider.callback)
                host.confirm(DeviceConsentDecision.ALLOW)
                yield(); provider.emit(10001); assertFalse(call.isCompleted)
                provider.emit()
                val result = call.await()
                assertEquals(51.12, result["latitude"])
                assertEquals(1500.0, result["accuracyMeters"])
                assertEquals(123L, result["ageMs"])
                assertEquals("coarse", result["precision"])
                assertEquals(1, provider.removed)
                val fine = async { host.invoke("location.current", mapOf("accuracy" to "fine")) }
                yield(); assertNull(host.confirmation); provider.emit()
                assertEquals(51.123456, fine.await()["latitude"])
            } finally { host.close() }
        }
    }
    @Test fun rememberedConsentCannotBypassOsDenialOrStaleGrantMap() = runBlocking {
        withContext(Dispatchers.Main) {
            val store = MemoryDeviceConsentStore()
            store.grant(canonicalDeviceOrigin(cz.trety.seed.BuildConfig.WEBAPP_DEV_URL), DeviceConsentGroup.LOCATION)
            val provider = Provider()
            val host = host(Permission(), provider, store)
            try {
                var requested = emptyList<String>()
                host.launchLocationPermissions = { requested = it.toList() }
                val call = async { runCatching { host.invoke("location.current", emptyMap()) } }
                yield(); assertNull(host.confirmation)
                assertEquals(listOf(LocationCurrent.COARSE), requested)
                host.locationPermissionResult(mapOf(LocationCurrent.COARSE to true))
                assertEquals("PERMISSION_DENIED", (call.await().exceptionOrNull() as DeviceCapabilityError).code)
                assertNull(provider.callback)
            } finally { host.close() }
        }
    }
    @Test fun fakeApproximateFinePreferenceDoesNotReprompt() = runBlocking {
        withContext(Dispatchers.Main) {
            val provider = Provider()
            val host = host(Permission(true, false), provider)
            try {
                host.launchLocationPermissions = { fail("Approximate permission must not trigger upgrade prompt") }
                repeat(2) {
                    val call = async { host.invoke("location.current", mapOf("accuracy" to "fine")) }
                    yield(); if (host.confirmation != null) host.confirm(DeviceConsentDecision.ALLOW)
                    yield(); provider.emit()
                    assertEquals("coarse", call.await()["precision"])
                }
            } finally { host.close() }
        }
    }
    @Test fun fakePermissionCancellationKeepsEveryHostOperationBusyUntilLateResult() = runBlocking {
        withContext(Dispatchers.Main) {
            val permission = Permission()
            val provider = Provider()
            val host = host(permission, provider)
            try {
                var launches = 0
                host.launchLocationPermissions = { launches++ }
                val first = async { host.invoke("location.current", mapOf("accuracy" to "fine")) }
                yield(); host.confirm(DeviceConsentDecision.ALLOW); yield()
                assertEquals(1, launches)
                first.cancelAndJoin()
                try { host.invoke("sensor.list", emptyMap()); fail() } catch (e: DeviceCapabilityError) { assertEquals("BUSY", e.code) }
                host.locationPermissionResult(mapOf(LocationCurrent.FINE to true))
                val second = async { host.invoke("location.current", emptyMap()) }
                yield(); assertEquals(2, launches); assertFalse(second.isCompleted)
                permission.coarse = true
                host.locationPermissionResult(mapOf(LocationCurrent.COARSE to true))
                yield(); provider.emit()
                assertEquals("coarse", second.await()["precision"])
            } finally { host.close() }
        }
    }
    @Test fun lifecycleStopReturnsStructuredCancelledButPermissionWaitSurvives() = runBlocking {
        withContext(Dispatchers.Main) {
            val owner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
            }
            owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            val foreground = LifecycleLocationForeground(owner.lifecycle)
            foreground.attach()
            val permission = Permission()
            val provider = Provider()
            val store = MemoryDeviceConsentStore()
            store.grant(canonicalDeviceOrigin(cz.trety.seed.BuildConfig.WEBAPP_DEV_URL), DeviceConsentGroup.LOCATION)
            val host = AndroidDeviceCapabilities(InstrumentationRegistry.getInstrumentation().targetContext, store,
                locationPermission = permission, locationProvider = provider, locationForeground = foreground)
            try {
                host.launchLocationPermissions = {}
                val waiting = async { runCatching { host.invoke("location.current", emptyMap()) } }
                yield()
                owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED
                assertFalse(waiting.isCompleted)
                permission.coarse = true; host.locationPermissionResult(emptyMap())
                yield(); assertNull(provider.callback); assertFalse(waiting.isCompleted)
                owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                yield(); assertNotNull(provider.callback)
                owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED
                assertEquals("CANCELLED", (waiting.await().exceptionOrNull() as DeviceCapabilityError).code)
                assertEquals(1, provider.removed)
                // No background timer can acquire another fix while the runtime service remains alive.
                try { host.invoke("location.current", emptyMap()); fail() } catch (e: DeviceCapabilityError) { assertEquals("UNAVAILABLE", e.code) }
            } finally { host.close(); foreground.detach() }
        }
    }
    @Test fun fakeDisabledTimeoutCancellationAndLateProviderCleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            val store = MemoryDeviceConsentStore()
            store.grant(canonicalDeviceOrigin(cz.trety.seed.BuildConfig.WEBAPP_DEV_URL), DeviceConsentGroup.LOCATION)
            val provider = Provider()
            val host = host(Permission(true, true), provider, store)
            try {
                provider.enabled = emptyList()
                try { host.invoke("location.current", emptyMap()); fail() } catch (e: DeviceCapabilityError) { assertEquals("UNAVAILABLE", e.code) }
                provider.enabled = listOf("network")
                try { host.invoke("location.current", mapOf("timeoutMs" to 1000)); fail() } catch (e: DeviceCapabilityError) { assertEquals("TIMEOUT", e.code) }
                assertEquals(1, provider.removed)
                val first = async { host.invoke("location.current", emptyMap()) }
                yield(); val old = provider.callback!!; first.cancelAndJoin()
                assertEquals(2, provider.removed)
                val second = async { host.invoke("location.current", emptyMap()) }
                yield(); old(LocationFix(1.0, 1.0, 1.0, 1L, 20000L)); assertFalse(second.isCompleted)
                host.close(); second.join()
                assertTrue(second.isCancelled); assertEquals(3, provider.removed)
            } finally { host.close() }
        }
    }
}
