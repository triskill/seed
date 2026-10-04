package com.seed.app.device

import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.platform.app.InstrumentationRegistry
import com.seed.app.BuildConfig
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated consent only. No camera, GPS, permission changes or production grants. */
class NativeSensorStreamingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun available() = context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    private fun store() = MemoryDeviceConsentStore().also {
        it.grant(canonicalDeviceOrigin(BuildConfig.WEBAPP_DEV_URL), DeviceConsentGroup.SENSORS)
    }
    @Test fun realAccelerometerMultipleEventsAndIdempotentStop(): Unit = runBlocking {
        assumeTrue(available())
        withContext(Dispatchers.Main) {
            val host = AndroidDeviceCapabilities(context, store())
            val events = mutableListOf<Map<String, Any?>>()
            try {
                val ack = host.subscribe(mapOf("type" to 1, "rateHz" to 30)) {
                    events.add(it)
                    if (it["event"] == "sample") host.acknowledge(it["subscriptionId"] as String, it["sequence"] as Long)
                }
                val id = ack["subscriptionId"] as String
                withTimeout(10000) { while (events.count { it["event"] == "sample" } < 3) delay(50) }
                assertEquals(mapOf("stopped" to true), host.unsubscribe(id))
                assertEquals(mapOf("stopped" to true), host.unsubscribe(id))
                val count = events.size
                delay(200)
                assertEquals(count, events.size)
                assertEquals("closed", events.last()["event"])
            } finally { host.close() }
        }
    }
    @Test fun limitUniqueIdsRevocationAndSinkFailureCleanup(): Unit = runBlocking {
        assumeTrue(available())
        withContext(Dispatchers.Main) {
            val store = store()
            val host = AndroidDeviceCapabilities(context, store)
            try {
                val events = mutableListOf<Map<String, Any?>>()
                val ids = (1..4).map { host.subscribe(mapOf("type" to 1)) { events.add(it) }["subscriptionId"] as String }
                assertEquals(4, ids.toSet().size)
                val failure = runCatching { host.subscribe(mapOf("type" to 1)) {} }.exceptionOrNull() as DeviceCapabilityError
                assertEquals("BUSY", failure.code)
                store.revoke(canonicalDeviceOrigin(BuildConfig.WEBAPP_DEV_URL), DeviceConsentGroup.SENSORS)
                delay(150)
                assertEquals(4, events.count { it["event"] == "closed" })
                assertTrue(events.filter { it["event"] == "closed" }.all { (it["error"] as Map<*, *>)["code"] == "PERMISSION_DENIED" })
                store.grant(canonicalDeviceOrigin(BuildConfig.WEBAPP_DEV_URL), DeviceConsentGroup.SENSORS)
                host.subscribe(mapOf("type" to 1)) { throw IllegalStateException("sink") }
                delay(200)
                val replacement = host.subscribe(mapOf("type" to 1)) {}["subscriptionId"] as String
                assertFalse(replacement in ids)
                ids.forEach { host.unsubscribe(it) }
                host.unsubscribe(replacement)
            } finally { host.close() }
        }
    }
    @Test fun backgroundStopsWithoutResumeAndCloseIsPermanent(): Unit = runBlocking {
        assumeTrue(available())
        withContext(Dispatchers.Main) {
            var visible = true
            val callbacks = mutableSetOf<() -> Unit>()
            val foreground = object : LocationForeground {
                override fun isVisible() = visible
                override suspend fun awaitResumed() = Unit
                override fun onStop(callback: () -> Unit): () -> Unit { callbacks.add(callback); return { callbacks.remove(callback) } }
            }
            val host = AndroidDeviceCapabilities(context, store(), locationForeground = foreground)
            val events = mutableListOf<Map<String, Any?>>()
            try {
                host.subscribe(mapOf("type" to 1)) { events.add(it) }
                visible = false; callbacks.toList().forEach { it() }
                assertEquals("CANCELLED", (events.last()["error"] as Map<*, *>)["code"])
                val denied = runCatching { host.subscribe(mapOf("type" to 1)) {} }.exceptionOrNull() as DeviceCapabilityError
                assertEquals("UNAVAILABLE", denied.code)
                visible = true
                val count = events.size; delay(150); assertEquals(count, events.size)
                host.close()
                val closed = runCatching { host.subscribe(mapOf("type" to 1)) {} }.exceptionOrNull() as DeviceCapabilityError
                assertEquals("CANCELLED", closed.code)
            } finally { host.close() }
        }
    }
    @Test fun noAckBoundsOutputAndTimesOut(): Unit = runBlocking {
        assumeTrue(available())
        withContext(Dispatchers.Main) {
            val host = AndroidDeviceCapabilities(context, store())
            val events = mutableListOf<Map<String, Any?>>()
            try {
                host.subscribe(mapOf("type" to 1, "rateHz" to 60)) { events.add(it) }
                withTimeout(10000) { while (events.none { it["event"] == "sample" }) delay(50) }
                delay(300)
                assertEquals(1, events.count { it["event"] == "sample" })
                withTimeout(12000) { while (events.none { it["event"] == "closed" }) delay(50) }
                assertEquals("TIMEOUT", (events.last()["error"] as Map<*, *>)["code"])
                assertEquals(1, events.count { it["event"] == "sample" })
            } finally { host.close() }
        }
    }
    @Test fun onceDoesNotPersistAndCanceledApprovalCreatesNoStream(): Unit = runBlocking {
        assumeTrue(available())
        withContext(Dispatchers.Main) {
            val store = MemoryDeviceConsentStore()
            val host = AndroidDeviceCapabilities(context, store)
            try {
                val pending = async { host.subscribe(mapOf("type" to 1)) {} }
                yield(); assertEquals("sensor.subscribe", host.confirmation)
                pending.cancelAndJoin()
                val next = async { host.subscribe(mapOf("type" to 1)) {} }
                yield(); host.confirm(DeviceConsentDecision.ONCE)
                val id = next.await()["subscriptionId"] as String
                assertFalse(store.isGranted(canonicalDeviceOrigin(BuildConfig.WEBAPP_DEV_URL), DeviceConsentGroup.SENSORS))
                delay(100)
                host.unsubscribe(id)
            } finally { host.close() }
        }
    }
}
