package cz.trety.seed.device

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.*
import java.util.UUID

internal fun sensorPermissionAvailable(context: Context, type: Int): Boolean {
    val permission = when (type) {
        Sensor.TYPE_HEART_RATE, Sensor.TYPE_HEART_BEAT -> android.Manifest.permission.BODY_SENSORS
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> if (Build.VERSION.SDK_INT >= 29) android.Manifest.permission.ACTIVITY_RECOGNITION else null
        else -> null
    }
    return permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** All registry mutations and sink calls are main-thread owned; raw callbacks only overwrite slots. */
internal class NativeSensorStreams(
    private val manager: SensorManager,
    private val foreground: LocationForeground,
    private val allowed: (Int) -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var closed = false
    private val streams = linkedMapOf<String, Stream>()
    private var removeStop: (() -> Unit)? = null
    private inner class Stream(val id: String, val type: Int, val rate: Int, val grant: () -> Boolean,
        val emit: (Map<String, Any?>) -> Unit) {
        val sampler = NativeSensorSampler(rate)
        val ack = NativeSensorAckGate()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                sampler.offer(mapOf("type" to type, "values" to event.values.map { it.toDouble().takeIf(Double::isFinite) },
                    "timestampNs" to event.timestamp, "accuracy" to event.accuracy))
            }
        }
        val pump = object : Runnable {
            override fun run() {
                if (streams[id] !== this@Stream) return
                if (!foreground.isVisible()) { stop(id, "CANCELLED", "App no longer visible"); return }
                if (!grant() || !allowed(type)) { stop(id, "PERMISSION_DENIED", "Sensor permission revoked"); return }
                val now = SystemClock.elapsedRealtimeNanos()
                if (ack.timedOut(now)) { stop(id, "TIMEOUT", "Sensor receiver acknowledgment timed out"); return }
                try {
                    if (ack.ready()) sampler.take(now)?.let {
                        val sequence = ack.send(now)!!
                        emit(mapOf("subscriptionId" to id, "event" to "sample", "sequence" to sequence, "sample" to it))
                    }
                } catch (_: Exception) { stop(id, "INTERNAL_ERROR", "Sensor callback failed"); return }
                if (streams[id] === this@Stream) main.postDelayed(this, sampler.deliveryDelayMillis)
            }
        }
    }
    fun start(params: Map<String, Any?>, grant: () -> Boolean, emit: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        check(Looper.myLooper() == Looper.getMainLooper())
        val type = (params["type"] as? Number)?.toDouble()
        val rate = if (params.containsKey("rateHz")) (params["rateHz"] as? Number)?.toDouble() else 30.0
        if (params.keys.any { it !in setOf("type", "rateHz") } || type == null || !type.isFinite() || type <= 0 || type > Int.MAX_VALUE || type % 1 != 0.0 ||
            rate == null || !rate.isFinite() || rate !in 1.0..60.0 || rate % 1 != 0.0)
            throw DeviceCapabilityError("INVALID_PARAMS", "Invalid sensor stream parameters")
        if (closed) throw DeviceCapabilityError("CANCELLED", "Sensor streams closed")
        if (!foreground.isVisible()) throw DeviceCapabilityError("UNAVAILABLE", "Sensors require a visible app")
        if (streams.size >= 4) throw DeviceCapabilityError("BUSY", "Sensor stream limit reached")
        if (!allowed(type.toInt()) || !grant()) throw DeviceCapabilityError("PERMISSION_DENIED", "Sensor permission unavailable")
        val sensor = manager.getDefaultSensor(type.toInt()) ?: throw DeviceCapabilityError("UNAVAILABLE", "Sensor unavailable")
        if (sensor.reportingMode != Sensor.REPORTING_MODE_CONTINUOUS && sensor.reportingMode != Sensor.REPORTING_MODE_ON_CHANGE)
            throw DeviceCapabilityError("UNAVAILABLE", "Sensor reporting mode cannot stream")
        val stream = Stream(UUID.randomUUID().toString(), type.toInt(), rate.toInt(), grant, emit)
        val worker = thread ?: HandlerThread("SeedSensorStreams").also { it.start(); thread = it }
        streams[stream.id] = stream
        try {
            if (!manager.registerListener(stream.listener, sensor, 1_000_000 / stream.rate, Handler(worker.looper)))
                throw DeviceCapabilityError("UNAVAILABLE", "Sensor cannot stream")
            if (removeStop == null) removeStop = foreground.onStop { stopAll("CANCELLED", "App no longer visible") }
            main.post(stream.pump)
        } catch (error: Exception) {
            stop(stream.id, "UNAVAILABLE", "Sensor registration failed")
            if (error is SecurityException) throw DeviceCapabilityError("PERMISSION_DENIED", "Sensor permission unavailable")
            throw error
        }
        return mapOf("subscriptionId" to stream.id, "type" to stream.type, "rateHz" to stream.rate)
    }
    fun stop(id: String, code: String = "CANCELLED", message: String = "Sensor stream stopped"): Map<String, Any?> {
        val stream = streams.remove(id) ?: return mapOf("stopped" to true)
        main.removeCallbacks(stream.pump)
        manager.unregisterListener(stream.listener)
        stream.sampler.clear()
        if (streams.isEmpty()) { removeStop?.invoke(); removeStop = null; thread?.quitSafely(); thread = null }
        try { stream.emit(mapOf("subscriptionId" to id, "event" to "closed", "error" to mapOf("code" to code, "message" to message))) } catch (_: Exception) { }
        return mapOf("stopped" to true)
    }
    fun acknowledge(id: String, sequence: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        streams[id]?.ack?.acknowledge(sequence)
    }
    fun stopAll(code: String, message: String) { streams.keys.toList().forEach { stop(it, code, message) } }
    fun close() { closed = true; stopAll("CANCELLED", "Sensor streams closed") }
}
