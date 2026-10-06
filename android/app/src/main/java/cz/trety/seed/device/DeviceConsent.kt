package cz.trety.seed.device

import java.net.URI
import java.util.Locale

/** Stable persisted IDs, independent of method names and translated UI labels. */
enum class DeviceConsentGroup(val id: String) { CAMERA("camera"), SENSORS("sensors"), LOCATION("location") }
enum class DeviceConsentDecision { ONCE, ALLOW, DENY }

interface DeviceConsentStore {
    /** Load disk-backed state off the main thread before synchronous decisions. */
    suspend fun prepare() = Unit
    fun isGranted(origin: String, group: DeviceConsentGroup): Boolean
    fun grant(origin: String, group: DeviceConsentGroup)
    fun revoke(origin: String, group: DeviceConsentGroup)
}

class MemoryDeviceConsentStore : DeviceConsentStore {
    private val grants = mutableSetOf<Pair<String, DeviceConsentGroup>>()
    @Synchronized override fun isGranted(origin: String, group: DeviceConsentGroup) = (origin to group) in grants
    @Synchronized override fun grant(origin: String, group: DeviceConsentGroup) { grants.add(origin to group) }
    @Synchronized override fun revoke(origin: String, group: DeviceConsentGroup) { grants.remove(origin to group) }
}

internal class DeviceConsentApproval(
    private val store: DeviceConsentStore,
    private val origin: String,
    private val group: DeviceConsentGroup,
) {
    private var active = true
    @Synchronized fun decide(decision: DeviceConsentDecision): Boolean {
        if (!active) return false
        active = false
        if (decision == DeviceConsentDecision.ALLOW) store.grant(origin, group)
        return true
    }
    @Synchronized fun cancel() { active = false }
}

fun canonicalDeviceOrigin(url: String): String {
    val uri = try { URI(url) } catch (error: Exception) { throw IllegalArgumentException("Invalid device origin", error) }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") { "Unsupported device origin" }
    require(uri.userInfo == null && !uri.host.isNullOrBlank()) { "Invalid device origin" }
    val port = if (uri.port == -1) { if (scheme == "https") 443 else 80 } else uri.port
    require(port in 1..65535) { "Invalid device origin port" }
    return "$scheme://${uri.host.lowercase(Locale.ROOT)}:$port"
}

fun deviceConsentGroup(method: String): DeviceConsentGroup? = when (method) {
    "camera.capture" -> DeviceConsentGroup.CAMERA
    "location.current" -> DeviceConsentGroup.LOCATION
    "sensor.list", "sensor.read", "sensor.subscribe" -> DeviceConsentGroup.SENSORS
    else -> null
}
