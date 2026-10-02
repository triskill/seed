package com.seed.app.device

import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeDeviceCapabilitiesTest {
    @Test fun previewIsBoundedAndContainsNoFilePath() {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val result = encodeCameraPreview(bitmap)
        assertTrue((result["width"] as Int) <= 512)
        assertTrue((result["height"] as Int) <= 512)
        assertEquals("image/jpeg", result["mimeType"])
        assertEquals(true, result["preview"])
        val url = result["dataUrl"] as String
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertTrue(android.util.Base64.decode(url.substringAfter(','), 0).size <= 256 * 1024)
        assertTrue(bitmap.isRecycled)
    }
    @Test fun fakeCameraCancellationAndLateCompletion() {
        val slot = CameraResultSlot<Bitmap?>()
        assertTrue(slot.reserve { fail("abandoned result") })
        slot.abandon()
        assertFalse(slot.reserve {})
        slot.complete(null)
        var received = false
        assertTrue(slot.reserve { received = true })
        slot.complete(null)
        assertTrue(received)
        slot.close()
        slot.complete(null)
    }
    @Test fun fakeCameraHostKeepsCancelledSlotAndRecyclesLateBitmap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null)
        withContext(Dispatchers.Main) {
            val host = AndroidDeviceCapabilities(context)
            var launches = 0
            host.launchCamera = { launches++ }
            val first = async { host.invoke("camera.capture", emptyMap()) }
            yield()
            host.confirm(true)
            yield()
            assertEquals(1, launches)
            first.cancelAndJoin()
            try {
                host.invoke("camera.capture", emptyMap())
                fail("Expected BUSY until old activity returns")
            } catch (error: DeviceCapabilityError) { assertEquals("BUSY", error.code) }
            val old = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            host.cameraResult(old)
            assertTrue(old.isRecycled)
            val second = async { host.invoke("camera.capture", emptyMap()) }
            yield()
            host.confirm(true)
            yield()
            host.cameraResult(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
            assertEquals(true, second.await()["preview"])
            assertEquals(2, launches)
            host.close()
            val late = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            host.cameraResult(late)
            assertTrue(late.isRecycled)
        }
    }
    @Test fun consentCancellationAndDisposalNeverLaunchCamera() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) {
            val host = AndroidDeviceCapabilities(context)
            host.launchCamera = { fail("Camera must not launch") }
            val pending = async { host.invoke("sensor.read", mapOf("type" to Sensor.TYPE_ACCELEROMETER)) }
            yield()
            assertEquals("sensor.read", host.confirmation)
            try {
                host.invoke("sensor.read", mapOf("type" to Sensor.TYPE_ACCELEROMETER))
                fail("Expected BUSY")
            } catch (error: DeviceCapabilityError) { assertEquals("BUSY", error.code) }
            pending.cancelAndJoin()
            assertNull(host.confirmation)
            val disposed = async { host.invoke("sensor.read", mapOf("type" to Sensor.TYPE_ACCELEROMETER)) }
            yield()
            host.close()
            disposed.join()
            assertTrue(disposed.isCancelled)
            assertNull(host.confirmation)
            host.cameraResult(null)
        }
    }
    @Test fun realAccelerometerHostReadingRequiresConsent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(SensorManager::class.java)
        org.junit.Assume.assumeNotNull(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
        androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use {
            withContext(Dispatchers.Main) {
                val host = AndroidDeviceCapabilities(context)
                try {
                    val reading = async { host.invoke("sensor.read", mapOf("type" to Sensor.TYPE_ACCELEROMETER, "timeoutMs" to 3000)) }
                    yield()
                    assertEquals("sensor.read", host.confirmation)
                    assertFalse(reading.isCompleted)
                    host.confirm(true)
                    val result = withTimeout(5000) { reading.await() }
                    assertEquals(Sensor.TYPE_ACCELEROMETER, result["type"])
                    assertTrue((result["values"] as List<*>).size >= 3)
                    assertTrue(result["timestampNs"] is Long)
                    assertNull(host.confirmation)
                } finally { host.close() }
            }
        }
    }

    @Test fun realSensorMetadataAndOptionalOneShot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(SensorManager::class.java)
        val sensors = manager.getSensorList(Sensor.TYPE_ALL)
        assertTrue(sensorMetadata(manager)["sensors"] is List<*>)
        val sensor = sensors.firstOrNull { it.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS }
        if (sensor != null) {
            try {
                val result = readSensorOnce(manager, sensor.type, 100)
                assertEquals(sensor.type, result["type"])
                assertTrue(result["timestampNs"] is Long)
                assertTrue((result["values"] as List<*>).all { it == null || (it as Double).isFinite() })
            } catch (error: DeviceCapabilityError) {
                assertTrue(error.code in setOf("TIMEOUT", "PERMISSION_DENIED", "UNAVAILABLE"))
            }
        }
    }
}
