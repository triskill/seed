package cz.trety.seed.device

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cz.trety.seed.R
import cz.trety.seed.ui.settings.DeviceAccessSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeLocationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun locationConsentAndSettingsUseLocationLabelsAndIsolatedGrants() {
        val store = MemoryDeviceConsentStore()
        val origin = "http://localhost:8080"
        val permission = object : LocationPermission { override fun grants() = LocationGrants(false, false) }
        val provider = object : LocationProvider {
            override fun enabledProviders(): List<String> { fail("Denied consent must not read providers"); return emptyList() }
            override fun elapsedRealtimeMs() = 0L
            override fun subscribe(provider: String, fix: (LocationFix) -> Unit, unavailable: () -> Unit): () -> Unit = { }
        }
        val host = AndroidDeviceCapabilities(compose.activity, store, origin, permission, provider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val showSettings = androidx.compose.runtime.mutableStateOf(false)
        try {
            host.launchLocationPermissions = { fail("No Android permission prompt in this test") }
            compose.setContent {
                MaterialTheme { Column {
                    if (showSettings.value) DeviceAccessSettings(store, origin)
                    DeviceConsentDialog(host)
                } }
            }
            scope.launch { try { host.invoke("location.current", emptyMap()) } catch (_: DeviceCapabilityError) { } }
            compose.waitUntil(5000) { host.confirmation != null }
            compose.onNodeWithText(compose.activity.getString(R.string.device_location_confirmation)).assertIsDisplayed()
            compose.onNodeWithText("Deny").performClick()
            compose.waitUntil(5000) { host.confirmation == null }
            assertFalse(store.isGranted(origin, DeviceConsentGroup.LOCATION))
            store.grant(origin, DeviceConsentGroup.LOCATION)
            compose.runOnIdle { showSettings.value = true }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("device-revoke-location").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Revoke Location access").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { !store.isGranted(origin, DeviceConsentGroup.LOCATION) }
            compose.onNodeWithText("No remembered Location access").assertIsDisplayed()
        } finally { host.close(); scope.cancel() }
    }
}
