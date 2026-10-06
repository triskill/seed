package cz.trety.seed.device

import org.junit.Assert.*
import org.junit.Test

class DeviceConsentTest {
    @Test fun canonicalOriginsSharePathsButNotPortsOrSchemes() {
        assertEquals("http://localhost:8080", canonicalDeviceOrigin("HTTP://LOCALHOST:8080/apps/a?q=1"))
        assertEquals("https://example.com:443", canonicalDeviceOrigin("https://Example.com/path"))
        assertNotEquals(canonicalDeviceOrigin("http://localhost:8080"), canonicalDeviceOrigin("http://localhost:8081"))
        assertNotEquals(canonicalDeviceOrigin("http://example.com"), canonicalDeviceOrigin("https://example.com"))
    }
    @Test fun rememberedGroupsAreOriginBoundRevocableAndSharedBySensors() {
        val store = MemoryDeviceConsentStore()
        val origin = canonicalDeviceOrigin("http://localhost:8080/apps/a")
        assertFalse(store.isGranted(origin, DeviceConsentGroup.SENSORS))
        store.grant(origin, DeviceConsentGroup.SENSORS)
        assertEquals(DeviceConsentGroup.SENSORS, deviceConsentGroup("sensor.list"))
        assertEquals(DeviceConsentGroup.SENSORS, deviceConsentGroup("sensor.read"))
        assertTrue(store.isGranted(canonicalDeviceOrigin("http://localhost:8080/apps/b"), deviceConsentGroup("sensor.read")!!))
        assertFalse(store.isGranted(origin, DeviceConsentGroup.CAMERA))
        assertFalse(store.isGranted(canonicalDeviceOrigin("http://localhost:8081"), DeviceConsentGroup.SENSORS))
        assertNull(deviceConsentGroup("unknown"))
        store.revoke(origin, DeviceConsentGroup.SENSORS)
        assertFalse(store.isGranted(origin, DeviceConsentGroup.SENSORS))
    }
    @Test fun onlyLiveRememberedApprovalGrantsAndAResponseCannotBeReplayed() {
        val store = MemoryDeviceConsentStore()
        val origin = canonicalDeviceOrigin("http://localhost:8080")
        for (decision in DeviceConsentDecision.entries) {
            val approval = DeviceConsentApproval(store, origin, DeviceConsentGroup.SENSORS)
            assertTrue(approval.decide(decision))
            assertFalse(approval.decide(DeviceConsentDecision.ALLOW))
            assertEquals(decision == DeviceConsentDecision.ALLOW, store.isGranted(origin, DeviceConsentGroup.SENSORS))
            store.revoke(origin, DeviceConsentGroup.SENSORS)
        }
        val cancelled = DeviceConsentApproval(store, origin, DeviceConsentGroup.SENSORS)
        cancelled.cancel()
        assertFalse(cancelled.decide(DeviceConsentDecision.ALLOW))
        assertFalse(store.isGranted(origin, DeviceConsentGroup.SENSORS))
    }

    @Test fun locationGrantsAreIndependentAndRevocable() {
        val store = MemoryDeviceConsentStore()
        val origin = canonicalDeviceOrigin("http://localhost:8080/apps/a")
        val group = deviceConsentGroup("location.current")!!
        assertEquals(DeviceConsentGroup.LOCATION, group)
        store.grant(origin, DeviceConsentGroup.SENSORS)
        store.grant(origin, DeviceConsentGroup.CAMERA)
        assertFalse(store.isGranted(origin, group))
        assertTrue(DeviceConsentApproval(store, origin, group).decide(DeviceConsentDecision.ALLOW))
        assertTrue(store.isGranted(canonicalDeviceOrigin("http://localhost:8080/apps/b"), group))
        assertFalse(store.isGranted(canonicalDeviceOrigin("http://localhost:8081"), group))
        store.revoke(origin, group)
        assertFalse(store.isGranted(origin, group))
        assertTrue(store.isGranted(origin, DeviceConsentGroup.CAMERA))
        assertTrue(store.isGranted(origin, DeviceConsentGroup.SENSORS))
    }
    @Test fun invalidOriginsAreRejected() {
        for (url in listOf("file:///tmp/a", "http://user:password@example.com", "not a url")) {
            try { canonicalDeviceOrigin(url); fail(url) } catch (_: IllegalArgumentException) { }
        }
    }
}
