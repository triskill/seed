package com.seed.app.device

import com.squareup.moshi.Moshi
import java.net.URI

interface DeviceCapabilityHost {
    suspend fun invoke(method: String, params: Map<String, Any?>): Map<String, Any?>
    fun close()
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
    fun requestId(input: String): String? = try {
        if (input.toByteArray().size > 8192) null else ((json.fromJson(input) as? Map<*, *>)?.get("id") as? String)?.takeIf(::validId)
    } catch (_: Exception) { null }
    fun capabilities(): Map<String, Any?> {
        val emptySchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>(), "additionalProperties" to false)
        val resultSchemas = mapOf(
            "capabilities.list" to mapOf("type" to "object", "required" to listOf("capabilities", "limitations"), "properties" to mapOf("capabilities" to mapOf("type" to "array", "items" to mapOf("type" to "object")), "limitations" to mapOf("type" to "array", "items" to mapOf("type" to "string")))),
            "camera.capture" to mapOf("type" to "object", "required" to listOf("dataUrl", "width", "height", "mimeType", "preview"), "properties" to mapOf("dataUrl" to mapOf("type" to "string"), "width" to mapOf("type" to "integer"), "height" to mapOf("type" to "integer"), "mimeType" to mapOf("const" to "image/jpeg"), "preview" to mapOf("const" to true))),
            "sensor.list" to mapOf("type" to "object", "required" to listOf("sensors"), "properties" to mapOf("sensors" to mapOf("type" to "array", "items" to mapOf("type" to "object", "description" to "Sensor type, name, vendor, version, stringType, maxRange, resolution, power, minDelay, reportingMode and wakeUp")))),
            "sensor.read" to mapOf("type" to "object", "required" to listOf("type", "values", "timestampNs", "accuracy"), "properties" to mapOf("type" to mapOf("type" to "integer"), "values" to mapOf("type" to "array", "items" to mapOf("type" to listOf("number", "null"))), "timestampNs" to mapOf("type" to "integer"), "accuracy" to mapOf("type" to listOf("integer", "null"))))
        )
        fun entry(method: String, description: String, schema: Map<String, Any>, limitations: List<String>) = mapOf("method" to method, "description" to description, "paramsSchema" to schema, "resultSchema" to resultSchemas.getValue(method), "limitations" to limitations)
        return mapOf("protocolVersion" to 1,
            "consent" to mapOf("scope" to "origin+capability", "choices" to listOf("Allow once", "Allow", "Deny"),
                "groups" to mapOf("camera" to listOf("camera.capture"), "sensors" to listOf("sensor.list", "sensor.read")),
                "revocation" to "Settings > Device access", "androidPermissions" to "Still required independently"),
            "limits" to mapOf("requestMaxBytes" to 8192, "requestIdPattern" to "[A-Za-z0-9_-]{1,64}", "maxConcurrentOperations" to 1, "hostTimeoutMs" to 120000),
            "reply" to mapOf("success" to "{v:1,id,ok:true,result}", "failure" to "{v:1,id,ok:false,error:{code,message}}", "errorCodes" to listOf("INVALID_REQUEST", "UNKNOWN_METHOD", "BUSY", "PERMISSION_DENIED", "UNAVAILABLE", "TIMEOUT", "CANCELLED", "INTERNAL_ERROR")),
            "capabilities" to listOf(
            entry("capabilities.list", "Describe approved Android capabilities", emptySchema, emptyList()),
            entry("camera.capture", "Open system camera with approved Camera access", emptySchema, listOf("Consent required unless Allow grant is remembered",  "Bounded JPEG preview/data URL only; no full resolution or video")),
            entry("sensor.list", "List available Android sensors with approved Sensors access", emptySchema, listOf("Consent required unless Allow grant is remembered",  "Availability depends on device and platform restrictions")),
            entry("sensor.read", "Obtain one sensor measurement with approved Sensors access", mapOf("type" to "object", "required" to listOf("type"), "additionalProperties" to false, "properties" to mapOf("type" to mapOf("type" to "integer", "minimum" to 1, "maximum" to Int.MAX_VALUE), "timeoutMs" to mapOf("type" to "integer", "minimum" to 100, "maximum" to 10000, "default" to 3000))), listOf("Consent required unless Allow grant is remembered", "One-shot only, no subscriptions; unsupported or restricted sensors fail"))
        ), "limitations" to listOf("One active operation", "Android app main frame only", "120 second host timeout"))
    }
}
