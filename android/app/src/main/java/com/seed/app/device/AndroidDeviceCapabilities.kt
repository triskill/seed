package com.seed.app.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.seed.app.R
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@Composable
fun rememberDeviceCapabilityHost(): DeviceCapabilityHost {
    val context = LocalContext.current
    val host = remember(context) { AndroidDeviceCapabilities(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) {
        host.cameraResult(it)
    }
    SideEffect { host.launchCamera = { launcher.launch(null) } }
    DisposableEffect(host) { onDispose { host.close() } }
    host.confirmation?.let { method ->
        AlertDialog(
            onDismissRequest = { host.confirm(false) },
            title = { Text(stringResource(R.string.device_confirmation_title)) },
            text = { Text(stringResource(if (method == "camera.capture") R.string.device_camera_confirmation else R.string.device_sensor_confirmation)) },
            confirmButton = { TextButton(onClick = { host.confirm(true) }) { Text(stringResource(R.string.device_allow)) } },
            dismissButton = { TextButton(onClick = { host.confirm(false) }) { Text(stringResource(R.string.device_deny)) } },
        )
    }
    return host
}

internal class AndroidDeviceCapabilities(private val context: Context) : DeviceCapabilityHost {
    var confirmation by mutableStateOf<String?>(null)
        private set
    var launchCamera: () -> Unit = {}
    private var approval: CancellableContinuation<Boolean>? = null
    private val camera = CameraResultSlot<Bitmap?>()
    private var operation: Job? = null
    private var closed = false
    private val manager = context.getSystemService(SensorManager::class.java)

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun invoke(method: String, params: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.Main.immediate) {
        if (closed) throw DeviceCapabilityError("CANCELLED", "Device access closed")
        if (operation != null || camera.isBusy()) throw DeviceCapabilityError("BUSY", "Device operation in progress")
        operation = currentCoroutineContext()[Job]
        try {
            when (method) {
                "sensor.list" -> { consent(method); sensorMetadata(manager) }
                "camera.capture" -> {
                    if (Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) == null)
                        throw DeviceCapabilityError("UNAVAILABLE", "No camera app available")
                    consent(method)
                    val bitmap = suspendCancellableCoroutine<Bitmap> { continuation ->
                        if (!camera.reserve { result ->
                            if (!continuation.isActive) result?.recycle()
                            else if (result == null) continuation.resumeWithException(DeviceCapabilityError("CANCELLED", "Camera cancelled"))
                            else continuation.resume(result) { result.recycle() }
                        }) {
                            continuation.resumeWithException(DeviceCapabilityError("BUSY", "Camera in progress"))
                        } else {
                            val token = camera.token()
                            continuation.invokeOnCancellation { onMain { camera.abandon(token) } }
                            try { launchCamera() } catch (error: Exception) {
                                camera.abandon()
                                camera.complete(null)
                                if (continuation.isActive) continuation.resumeWithException(nativeError(error))
                            }
                        }
                    }
                    // The owner recycles even if cancellation prevents the background block from starting.
                    try { withContext(Dispatchers.Default) { encodeCameraPreview(bitmap) } }
                    finally { if (!bitmap.isRecycled) bitmap.recycle() }
                }
                "sensor.read" -> {
                    val type = (params["type"] as? Number)?.toDouble()
                    val timeout = (params["timeoutMs"] as? Number)?.toDouble() ?: 3000.0
                    if (type == null || !type.isFinite() || type <= 0 || type > Int.MAX_VALUE || type % 1 != 0.0 ||
                        !timeout.isFinite() || timeout !in 100.0..10000.0 || timeout % 1 != 0.0)
                        throw DeviceCapabilityError("INVALID_PARAMS", "Invalid sensor parameters")
                    consent(method)
                    val permission = when (type.toInt()) {
                        Sensor.TYPE_HEART_RATE, Sensor.TYPE_HEART_BEAT -> android.Manifest.permission.BODY_SENSORS
                        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> if (android.os.Build.VERSION.SDK_INT >= 29) android.Manifest.permission.ACTIVITY_RECOGNITION else null
                        else -> null
                    }
                    if (permission != null && context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        throw DeviceCapabilityError("PERMISSION_DENIED", "Sensor permission unavailable")
                    readSensorOnce(manager, type.toInt(), timeout.toLong())
                }
                else -> throw DeviceCapabilityError("UNKNOWN_METHOD", "Unknown device method")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: DeviceCapabilityError) { throw error }
        catch (error: Exception) { throw nativeError(error) }
        finally { approval = null; confirmation = null; operation = null }
    }

    private suspend fun consent(method: String) {
        val allowed = suspendCancellableCoroutine<Boolean> { continuation ->
            approval = continuation
            confirmation = method
            continuation.invokeOnCancellation { onMain { if (approval === continuation) { approval = null; confirmation = null } } }
        }
        if (!allowed) throw DeviceCapabilityError("PERMISSION_DENIED", "Device access declined")
    }
    fun confirm(allowed: Boolean) {
        val pending = approval
        approval = null
        confirmation = null
        if (pending?.isActive == true) pending.resume(allowed)
    }
    fun cameraResult(bitmap: Bitmap?) {
        if (!camera.complete(bitmap)) bitmap?.recycle()
    }
    override fun close() {
        onMain {
            closed = true
            confirmation = null
            approval?.cancel()
            approval = null
            camera.close()
            operation?.cancel()
            launchCamera = {}
        }
    }
}

private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else Handler(Looper.getMainLooper()).post { action() }
}

private fun nativeError(error: Exception) = when (error) {
    is SecurityException -> DeviceCapabilityError("PERMISSION_DENIED", "Device permission unavailable")
    is android.content.ActivityNotFoundException -> DeviceCapabilityError("UNAVAILABLE", "Device activity unavailable")
    else -> DeviceCapabilityError("INTERNAL_ERROR", "Device operation failed")
}

/** Takes ownership of the bitmap; no paths, files, EXIF, or provider URIs leave native code. */
internal fun encodeCameraPreview(bitmap: Bitmap): Map<String, Any?> {
    var scaled: Bitmap? = null
    try {
        val ratio = minOf(1.0, 512.0 / maxOf(bitmap.width, bitmap.height))
        scaled = if (ratio < 1) Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).roundToInt()), maxOf(1, (bitmap.height * ratio).roundToInt()), true) else bitmap
        var bytes: ByteArray? = null
        for (quality in listOf(85, 65, 45, 25, 10)) {
            val output = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw DeviceCapabilityError("INTERNAL_ERROR", "Preview encoding failed")
            if (output.size() <= 256 * 1024) { bytes = output.toByteArray(); break }
        }
        val jpeg = bytes ?: throw DeviceCapabilityError("UNAVAILABLE", "Preview exceeds size limit")
        return mapOf("dataUrl" to "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}", "width" to scaled.width, "height" to scaled.height, "mimeType" to "image/jpeg", "preview" to true)
    } finally {
        if (scaled !== bitmap) scaled?.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

internal fun sensorMetadata(manager: SensorManager): Map<String, Any?> = mapOf("sensors" to manager.getSensorList(Sensor.TYPE_ALL).map {
    mapOf("type" to it.type, "name" to it.name, "vendor" to it.vendor, "version" to it.version, "stringType" to it.stringType,
        "maxRange" to finite(it.maximumRange), "resolution" to finite(it.resolution), "power" to finite(it.power), "minDelay" to it.minDelay, "reportingMode" to it.reportingMode, "wakeUp" to it.isWakeUpSensor)
})

private fun finite(value: Float): Double? = value.toDouble().takeIf { it.isFinite() }

internal suspend fun readSensorOnce(manager: SensorManager, type: Int, timeoutMs: Long): Map<String, Any?> = withContext(Dispatchers.Main.immediate) {
    val sensor = manager.getDefaultSensor(type) ?: throw DeviceCapabilityError("UNAVAILABLE", "Sensor unavailable")
    var listener: SensorEventListener? = null
    var trigger: TriggerEventListener? = null
    try {
        withTimeout(timeoutMs) {
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                suspendCancellableCoroutine<Map<String, Any?>> { continuation ->
                    val subscription = object : TriggerEventListener() {
                        override fun onTrigger(event: TriggerEvent) {
                            manager.cancelTriggerSensor(this, sensor)
                            if (continuation.isActive) continuation.resume(mapOf("type" to event.sensor.type, "values" to event.values.map(::finite), "timestampNs" to event.timestamp, "accuracy" to null))
                        }
                    }
                    trigger = subscription
                    continuation.invokeOnCancellation { manager.cancelTriggerSensor(subscription, sensor) }
                    if (!manager.requestTriggerSensor(subscription, sensor))
                        continuation.resumeWithException(DeviceCapabilityError("UNAVAILABLE", "Sensor cannot be read"))
                }
            } else suspendCancellableCoroutine { continuation ->
                val subscription = object : SensorEventListener {
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (continuation.isActive) continuation.resume(mapOf("type" to event.sensor.type, "values" to event.values.map(::finite), "timestampNs" to event.timestamp, "accuracy" to event.accuracy))
                    }
                }
                listener = subscription
                continuation.invokeOnCancellation { manager.unregisterListener(subscription) }
                if (!manager.registerListener(subscription, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper())))
                    continuation.resumeWithException(DeviceCapabilityError("UNAVAILABLE", "Sensor cannot be read"))
            }
        }
    } catch (error: TimeoutCancellationException) { throw DeviceCapabilityError("TIMEOUT", "Sensor read timed out") }
    catch (error: SecurityException) { throw DeviceCapabilityError("PERMISSION_DENIED", "Sensor permission unavailable") }
    finally {
        listener?.let { manager.unregisterListener(it) }
        trigger?.let { manager.cancelTriggerSensor(it, sensor) }
    }
}
