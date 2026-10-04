package com.seed.app.device

import com.squareup.moshi.Moshi
import java.net.URI

interface DeviceCapabilityHost {
    suspend fun invoke(method: String, params: Map<String, Any?>): Map<String, Any?>
    fun close()
}

interface DeviceStreamHost : DeviceCapabilityHost {
    suspend fun subscribe(params: Map<String, Any?>, emit: (Map<String, Any?>) -> Unit): Map<String, Any?>
    fun unsubscribe(subscriptionId: String): Map<String, Any?>
    fun acknowledge(subscriptionId: String, sequence: Long)
    fun stopStreams(code: String, message: String)
}

class DeviceCapabilityError(val code: String, message: String) : Exception(message)
data class DeviceRequest(val id: String, val method: String, val params: Map<String, Any?>)

object DeviceProtocol {
    private val json = Moshi.Builder().build().adapter(Any::class.java)
    private val ids = Regex("[A-Za-z0-9_-]{1,64}")
    fun validId(id: String) = ids.matches(id)
    fun encode(value: Any): String = json.toJson(value)
    fun origin(url: String?): String? = try {
        val uri = URI(url ?: "")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme !in setOf("http", "https") || host.isNullOrEmpty() || uri.rawUserInfo != null || uri.port < -1 || uri.port > 65535) null
        else "$scheme://${if (host.contains(':') && !host.startsWith('[')) "[$host]" else host}:${if (uri.port == -1) if (scheme == "https") 443 else 80 else uri.port}"
    } catch (_: Exception) { null }
    fun isTrusted(expected: String, source: String?, current: String?, mainFrame: Boolean): Boolean {
        val origin = origin(expected) ?: return false
        return mainFrame && origin(source) == origin && origin(current) == origin
    }
    fun parse(input: String): DeviceRequest {
        if (input.toByteArray(Charsets.UTF_8).size > 8192) invalid()
        val obj = try { json.fromJson(input) as? Map<*, *> } catch (_: Exception) { null } ?: invalid()
        if (obj.keys != setOf("v", "id", "method", "params") || obj["v"] != 1.0) invalid()
        val id = obj["id"] as? String ?: invalid()
        if (!validId(id)) invalid()
        val method = obj["method"] as? String ?: invalid()
        val raw = obj["params"] as? Map<*, *> ?: invalid()
        val params: Map<String, Any?> = when (method) {
            "capabilities.list", "camera.capture", "sensor.list" -> { if (raw.isNotEmpty()) invalid(); emptyMap() }
            "location.current" -> {
                if (raw.keys.any { it !in setOf("accuracy", "timeoutMs") }) invalid()
                val accuracy = if (raw.containsKey("accuracy")) raw["accuracy"] else "coarse"
                if (accuracy !in setOf("coarse", "fine")) invalid()
                val timeout = if (raw.containsKey("timeoutMs")) integer(raw["timeoutMs"], 1000, 60000) else 15000
                mapOf("accuracy" to accuracy, "timeoutMs" to timeout)
            }
            "sensor.subscribe" -> {
                if (!raw.containsKey("type") || raw.keys.any { it !in setOf("type", "rateHz") }) invalid()
                mapOf("type" to integer(raw["type"], 1, Int.MAX_VALUE), "rateHz" to if (raw.containsKey("rateHz")) integer(raw["rateHz"], 1, 60) else 30)
            }
            "sensor.unsubscribe" -> {
                if (raw.keys != setOf("subscriptionId")) invalid()
                val subscriptionId = raw["subscriptionId"] as? String ?: invalid()
                if (subscriptionId.isEmpty() || subscriptionId.length > 64) invalid()
                mapOf("subscriptionId" to subscriptionId)
            }
            "sensor.read" -> {
                if (!raw.containsKey("type") || raw.keys.any { it !in setOf("type", "timeoutMs") }) invalid()
                val type = integer(raw["type"], 1, Int.MAX_VALUE)
                val timeout = if (raw.containsKey("timeoutMs")) integer(raw["timeoutMs"], 100, 10000) else 3000
                mapOf("type" to type, "timeoutMs" to timeout)
            }
            else -> throw DeviceCapabilityError("UNKNOWN_METHOD", "Capability is not available")
        }
        return DeviceRequest(id, method, params)
    }
    private fun integer(value: Any?, min: Int, max: Int): Int {
        val n = value as? Double ?: invalid()
        if (!n.isFinite() || n < min || n > max || n % 1.0 != 0.0) invalid()
        return n.toInt()
    }
    private fun invalid(): Nothing = throw DeviceCapabilityError("INVALID_REQUEST", "Invalid device capability request")
    fun streamAck(input: String): Pair<String, Long>? = try {
        if (input.toByteArray(Charsets.UTF_8).size > 8192) null else {
            val obj = json.fromJson(input) as? Map<*, *>
            val id = obj?.get("subscriptionId") as? String
            val sequence = obj?.get("sequence") as? Double
            if (obj?.keys != setOf("v", "type", "subscriptionId", "sequence") || obj["v"] != 1.0 || obj["type"] != "stream_ack" || id.isNullOrEmpty() || id.length > 64 || sequence == null || !sequence.isFinite() || sequence < 1 || sequence > 9007199254740991.0 || sequence % 1 != 0.0) null
            else id to sequence.toLong()
        }
    } catch (_: Exception) { null }
    fun requestId(input: String): String? = try {
        if (input.toByteArray().size > 8192) null else ((json.fromJson(input) as? Map<*, *>)?.get("id") as? String)?.takeIf(::validId)
    } catch (_: Exception) { null }
    fun capabilities(): Map<String, Any?> {
        val emptySchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to false)
        val resultSchemas = mapOf(
            "sensor.subscribe" to mapOf("type" to "object", "required" to listOf("subscriptionId", "type", "rateHz"), "properties" to mapOf("subscriptionId" to mapOf("type" to "string", "maxLength" to 64), "type" to mapOf("type" to "integer"), "rateHz" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 60))),
            "sensor.unsubscribe" to mapOf("type" to "object", "required" to listOf("stopped"), "properties" to mapOf("stopped" to mapOf("type" to "boolean"))),
            "location.current" to mapOf("type" to "object", "required" to listOf("latitude", "longitude", "accuracyMeters", "timestampMs", "precision", "ageMs"), "properties" to mapOf(
                "latitude" to mapOf("type" to "number", "minimum" to -90, "maximum" to 90),
                "longitude" to mapOf("type" to "number", "minimum" to -180, "maximum" to 180),
                "accuracyMeters" to mapOf("type" to "number", "minimum" to 0),
                "timestampMs" to mapOf("type" to "integer"), "ageMs" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 10000),
                "precision" to mapOf("type" to "string", "enum" to listOf("coarse", "fine")))),
            "capabilities.list" to mapOf("type" to "object", "required" to listOf("capabilities", "limitations"), "properties" to mapOf("capabilities" to mapOf("type" to "array", "items" to mapOf("type" to "object")), "limitations" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
            "camera.capture" to mapOf("type" to "object", "required" to listOf("dataUrl", "width", "height", "mimeType", "preview"), "properties" to mapOf("dataUrl" to mapOf("type" to "string"), "width" to mapOf("type" to "integer"), "height" to mapOf("type" to "integer"), "mimeType" to mapOf("const" to "image/jpeg"), "preview" to mapOf("const" to true))),
            "sensor.list" to mapOf("type" to "object", "required" to listOf("sensors"), "properties" to mapOf("sensors" to mapOf("type" to "array", "items" to mapOf("type" to "object", "description" to "Sensor type, name, vendor, version, stringType, maxRange, resolution, power, minDelay, reportingMode and wakeUp")))),
            "sensor.read" to mapOf("type" to "object", "required" to listOf("type", "values", "timestampNs", "accuracy"), "properties" to mapOf("type" to mapOf("type" to "integer"), "values" to mapOf("type" to "array", "items" to mapOf("type" to listOf("number", "null"))), "timestampNs" to mapOf("type" to "integer"), "accuracy" to mapOf("type" to listOf("integer", "null"))))
        )
        fun entry(method: String, description: String, schema: Map<String, Any>, limitations: List<String>) = mapOf("method" to method, "description" to description, "paramsSchema" to schema, "resultSchema" to resultSchemas.getValue(method), "limitations" to limitations)
        return mapOf("protocolVersion" to 1,
            "consent" to mapOf("scope" to "origin+capability", "choices" to listOf("Allow once", "Allow", "Deny"),
                "groups" to mapOf("camera" to listOf("camera.capture"), "sensors" to listOf("sensor.list", "sensor.read", "sensor.subscribe"), "location" to listOf("location.current")),
                "revocation" to "Settings > Device access", "androidPermissions" to "Still required independently"),
            "limits" to mapOf("requestMaxBytes" to 8192, "requestIdPattern" to "[A-Za-z0-9_-]{1,64}", "maxConcurrentOperations" to 1, "maxSensorSubscriptions" to 4, "hostTimeoutMs" to 120000),
            "reply" to mapOf("stream" to "{v:1,type:'stream',id,subscriptionId,event:'sample',sequence,sample:{type,values,timestampNs,accuracy}} or {v:1,type:'stream',id,subscriptionId,event:'closed',error:{code,message}}", "streamAck" to "{v:1,type:'stream_ack',subscriptionId,sequence}; ACK after callback settles; one unacknowledged sample plus latest; 10 second ACK timeout", "success" to "{v:1,id,ok:true,result}", "failure" to "{v:1,id,ok:false,error:{code,message}}", "errorCodes" to listOf("INVALID_REQUEST", "UNKNOWN_METHOD", "BUSY", "PERMISSION_DENIED", "UNAVAILABLE", "TIMEOUT", "CANCELLED", "INTERNAL_ERROR")),
            "capabilities" to listOf(
            entry("capabilities.list", "Describe approved Android capabilities", emptySchema, emptyList()),
            entry("location.current", "Obtain one foreground location fix with approved Location access", mapOf("type" to "object", "additionalProperties" to false, "properties" to mapOf(
                "accuracy" to mapOf("type" to "string", "enum" to listOf("coarse", "fine"), "default" to "coarse"),
                "timeoutMs" to mapOf("type" to "integer", "minimum" to 1000, "maximum" to 60000, "default" to 15000))),
                listOf("Consent and Android foreground permission required independently", "One-shot only; no background tracking or subscriptions", "Fix age at most 10000ms; timeout removes listeners", "Coarse is quantized to a 0.01 degree grid with accuracy at least 1500m", "Fine preference falls back to coarse when Android grants approximate access; no upgrade prompt")),
            entry("camera.capture", "Open system camera with approved Camera access", emptySchema, listOf("Consent required unless Allow grant is remembered",  "Bounded JPEG preview/data URL only; no full resolution or video")),
            entry("sensor.list", "List available Android sensors with approved Sensors access", emptySchema, listOf("Consent required unless Allow grant is remembered",  "Availability depends on device and platform restrictions")),
            entry("sensor.subscribe", "Stream foreground sensor samples", mapOf("type" to "object", "required" to listOf("type"), "additionalProperties" to false, "properties" to mapOf("type" to mapOf("type" to "integer", "minimum" to 1, "maximum" to Int.MAX_VALUE), "rateHz" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 60, "default" to 30))), listOf("Sensors consent required", "Maximum four subscriptions; latest samples only", "Continuous/on-change sensors only; hardware rates are hints", "Navigation, background and revocation terminate streams")),
            entry("sensor.unsubscribe", "Stop a document-owned sensor subscription", mapOf("type" to "object", "required" to listOf("subscriptionId"), "additionalProperties" to false, "properties" to mapOf("subscriptionId" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 64))), listOf("Idempotent document-owned cleanup; no consent required")),
            entry("sensor.read", "Obtain one sensor measurement with approved Sensors access", mapOf("type" to "object", "required" to listOf("type"), "additionalProperties" to false, "properties" to mapOf("type" to mapOf("type" to "integer", "minimum" to 1, "maximum" to Int.MAX_VALUE), "timeoutMs" to mapOf("type" to "integer", "minimum" to 100, "maximum" to 10000, "default" to 3000))), listOf("Consent required unless Allow grant is remembered", "One-shot measurement; unsupported or restricted sensors fail"))
        ), "limitations" to listOf("One active operation", "Android app main frame only", "120 second host timeout"))
    }
}
