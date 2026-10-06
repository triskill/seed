package cz.trety.seed.device

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationCurrentTest {
    private class Permission(var coarse: Boolean = true, var fine: Boolean = true) : LocationPermission {
        override fun grants() = LocationGrants(coarse, fine)
    }
    private class Provider : LocationProvider {
        var enabled = listOf("network", "fused", "gps")
        var selected: String? = null
        var callback: ((LocationFix) -> Unit)? = null
        var failure: (() -> Unit)? = null
        var removed = 0
        override fun enabledProviders() = enabled
        override fun elapsedRealtimeMs() = 20000L
        override fun subscribe(provider: String, fix: (LocationFix) -> Unit, unavailable: () -> Unit): () -> Unit {
            selected = provider; callback = fix; failure = unavailable
            return { removed++ }
        }
        fun emit(age: Long = 321) { callback!!(LocationFix(51.123456, -0.123456, 4.0, 123456L, 20000L - age)) }
    }
    @Test fun coarseNeverLeaksFineAndFineUsesGps() = runTest {
        val provider = Provider()
        val location = LocationCurrent(Permission(), provider)
        val coarse = async { location.current(emptyMap()) }
        runCurrent(); provider.emit()
        val result = coarse.await()
        assertEquals(51.12, result["latitude"])
        assertEquals(-0.12, result["longitude"])
        assertEquals(1500.0, result["accuracyMeters"])
        assertEquals("coarse", result["precision"])
        assertEquals(321L, result["ageMs"])
        assertEquals(123456L, result["timestampMs"])
        assertEquals("network", provider.selected)
        assertEquals(1, provider.removed)
        val fine = async { location.current(mapOf("accuracy" to "fine")) }
        runCurrent(); provider.emit()
        assertEquals(51.123456, fine.await()["latitude"])
        assertEquals("gps", provider.selected)
        assertEquals(2, provider.removed)
    }
    @Test fun coarseRequestCanUseGpsWithFinePermissionWithoutExposingFineCoordinates() = runTest {
        val provider = Provider().apply { enabled = listOf("gps") }
        val location = LocationCurrent(Permission(), provider)
        val read = async { runCatching { location.current(emptyMap()) } }
        runCurrent()
        assertEquals("gps", provider.selected)
        provider.emit()
        val result = read.await().getOrThrow()
        assertEquals("coarse", result["precision"])
        assertEquals(51.12, result["latitude"])
        assertEquals(-0.12, result["longitude"])
        assertEquals(1500.0, result["accuracyMeters"])
        val approximateOnly = LocationCurrent(Permission(fine = false), Provider().apply { enabled = listOf("gps") })
        try { approximateOnly.current(emptyMap()); fail("GPS requires precise platform permission") }
        catch (error: DeviceCapabilityError) { assertEquals("UNAVAILABLE", error.code) }
    }

    @Test fun oldFixesAreIgnoredUntilFreshAndLateCallbacksCannotCompleteNextCall() = runTest {
        val provider = Provider()
        val location = LocationCurrent(Permission(), provider)
        val first = async { location.current(emptyMap()) }
        runCurrent(); provider.emit(10001)
        assertFalse(first.isCompleted)
        val oldCallback = provider.callback!!
        first.cancelAndJoin()
        assertEquals(1, provider.removed)
        val second = async { location.current(emptyMap()) }
        runCurrent(); oldCallback(LocationFix(1.0, 1.0, 1.0, 1, 20000))
        assertFalse(second.isCompleted)
        provider.emit(10000)
        assertEquals(10000L, second.await()["ageMs"])
        assertEquals(2, provider.removed)
    }
    @Test fun approximatePermissionSufficesWithoutUpgradePrompt() = runTest {
        val provider = Provider()
        val location = LocationCurrent(Permission(fine = false), provider)
        location.launchPermissions = { fail("No upgrade prompt") }
        repeat(2) {
            val call = async { location.current(mapOf("accuracy" to "fine")) }
            runCurrent(); provider.emit()
            assertEquals("coarse", call.await()["precision"])
            assertEquals(51.12, call.await()["latitude"])
        }
    }
    @Test fun permissionRequestPrecisionAndRecheckIgnoreFakeGrant() = runTest {
        val permission = Permission(false, false)
        val provider = Provider()
        val location = LocationCurrent(permission, provider)
        var requested = emptyList<String>()
        location.launchPermissions = { requested = it.toList() }
        val first = async { runCatching { location.current(emptyMap()) } }
        runCurrent()
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), requested)
        location.permissionResult(mapOf("android.permission.ACCESS_COARSE_LOCATION" to true))
        assertEquals("PERMISSION_DENIED", (first.await().exceptionOrNull() as DeviceCapabilityError).code)
        assertNull(provider.selected)
        val fine = async { location.current(mapOf("accuracy" to "fine")) }
        runCurrent()
        assertEquals(setOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"), requested.toSet())
        permission.coarse = true
        location.permissionResult(emptyMap()) // actual OS state, not the callback map, is authoritative
        runCurrent(); provider.emit()
        assertEquals("coarse", fine.await()["precision"])
    }
    @Test fun cancelledPermissionSlotStaysBusyUntilOldResultReturns() = runTest {
        val permission = Permission(false, false)
        val provider = Provider()
        val location = LocationCurrent(permission, provider)
        location.launchPermissions = {}
        val first = async { location.current(emptyMap()) }
        runCurrent(); first.cancelAndJoin()
        assertTrue(location.isPermissionBusy())
        try { location.current(emptyMap()); fail() } catch (e: DeviceCapabilityError) { assertEquals("BUSY", e.code) }
        location.permissionResult(emptyMap())
        assertFalse(location.isPermissionBusy())
        val second = async { location.current(emptyMap()) }
        runCurrent(); assertFalse(second.isCompleted)
        permission.coarse = true; location.permissionResult(emptyMap())
        runCurrent(); provider.emit()
        assertEquals("coarse", second.await()["precision"])
        location.close()
    }
    @Test fun disabledTimeoutAndCancellationCleanUp() = runTest {
        val provider = Provider()
        val location = LocationCurrent(Permission(), provider)
        provider.enabled = emptyList()
        try { location.current(emptyMap()); fail() } catch (e: DeviceCapabilityError) { assertEquals("UNAVAILABLE", e.code) }
        provider.enabled = listOf("network")
        val timeout = async { runCatching { location.current(mapOf("timeoutMs" to 1000)) } }
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals("TIMEOUT", (timeout.await().exceptionOrNull() as DeviceCapabilityError).code)
        assertEquals(1, provider.removed)
        val disabled = async { runCatching { location.current(emptyMap()) } }
        runCurrent(); provider.failure!!()
        assertEquals("UNAVAILABLE", (disabled.await().exceptionOrNull() as DeviceCapabilityError).code)
        assertEquals(2, provider.removed)
        val cancelled = async { location.current(emptyMap()) }
        runCurrent(); cancelled.cancelAndJoin()
        assertEquals(3, provider.removed)
    }
    private class Foreground : LocationForeground {
        var visible = true
        var stop: (() -> Unit)? = null
        val state = kotlinx.coroutines.flow.MutableStateFlow(true)
        override fun isVisible() = visible
        override suspend fun awaitResumed() { state.first { it } }
        override fun onStop(callback: () -> Unit): () -> Unit { stop = callback; return { stop = null } }
        fun stopped() { visible = false; state.value = false; stop?.invoke() }
    }
    @Test fun backgroundStartDeniedAndStopCancelsReadButNotPermissionWait() = runTest {
        val permission = Permission(false, false)
        val provider = Provider()
        val foreground = Foreground()
        val location = LocationCurrent(permission, provider, foreground)
        foreground.stopped()
        try { location.current(emptyMap()); fail() } catch (e: DeviceCapabilityError) { assertEquals("UNAVAILABLE", e.code) }
        assertFalse(location.isPermissionBusy())
        foreground.visible = true; foreground.state.value = true
        location.launchPermissions = {}
        val waiting = async { runCatching { location.current(emptyMap()) } }
        runCurrent(); foreground.stopped()
        assertFalse(waiting.isCompleted)
        permission.coarse = true; location.permissionResult(emptyMap())
        runCurrent(); assertNull(provider.selected); assertFalse(waiting.isCompleted)
        foreground.visible = true; foreground.state.value = true
        runCurrent(); assertNotNull(provider.selected)
        foreground.stopped(); runCurrent()
        assertEquals("CANCELLED", (waiting.await().exceptionOrNull() as DeviceCapabilityError).code)
        assertEquals(1, provider.removed)
    }
    @Test fun permissionTimeoutRetainsExternalSlotAndRevocationBeforeFixFails() = runTest {
        val permission = Permission(false, false)
        val provider = Provider()
        val location = LocationCurrent(permission, provider)
        location.launchPermissions = {}
        val request = async { runCatching { location.current(mapOf("timeoutMs" to 1000)) } }
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals("TIMEOUT", (request.await().exceptionOrNull() as DeviceCapabilityError).code)
        assertTrue(location.isPermissionBusy())
        location.permissionResult(emptyMap())
        assertFalse(location.isPermissionBusy())
        permission.coarse = true
        val read = async { runCatching { location.current(emptyMap()) } }
        runCurrent(); permission.coarse = false; provider.emit()
        assertEquals("PERMISSION_DENIED", (read.await().exceptionOrNull() as DeviceCapabilityError).code)
        assertEquals(1, provider.removed)
    }
    @Test fun permissionDowngradeBeforeFixCannotLeakFine() = runTest {
        val permission = Permission()
        val provider = Provider()
        val location = LocationCurrent(permission, provider)
        val call = async { location.current(mapOf("accuracy" to "fine")) }
        runCurrent(); permission.fine = false; provider.emit()
        assertEquals(51.12, call.await()["latitude"])
    }
    @Test fun invalidNativeParamsRejectedBeforePermissionsOrProvider() = runTest {
        val provider = Provider()
        val location = LocationCurrent(Permission(), provider)
        for (params in listOf(mapOf("accuracy" to "precise"), mapOf("accuracy" to null), mapOf("timeoutMs" to 999), mapOf("timeoutMs" to 1000.5), mapOf("watch" to true))) {
            try { location.current(params); fail() } catch (e: DeviceCapabilityError) { assertEquals("INVALID_PARAMS", e.code) }
        }
        assertNull(provider.selected)
    }
}
