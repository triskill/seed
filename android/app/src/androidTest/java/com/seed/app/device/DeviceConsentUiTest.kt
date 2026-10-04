package com.seed.app.device

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.seed.app.ui.settings.DeviceAccessSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeviceConsentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exactThreeLabelsRememberGrantAndSettingsRevokeWithoutProviders() {
        val context = compose.activity
        val name = "device-consent-ui-${java.util.UUID.randomUUID()}"
        val store = PreferencesDeviceConsentStore(context, name)
        val origin = "http://localhost:8080"
        val host = AndroidDeviceCapabilities(context, store, origin)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val showSettings = androidx.compose.runtime.mutableStateOf(false)
        try {
            compose.setContent {
                MaterialTheme {
                    Column {
                        if (showSettings.value) DeviceAccessSettings(store, origin)
                        DeviceConsentDialog(host)
                    }
                }
            }
            scope.launch { host.invoke("sensor.list", emptyMap()) }
            compose.waitUntil(5000) { host.confirmation != null }
            compose.onNodeWithText("Allow once").assertIsDisplayed()
            compose.onNodeWithText("Allow").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { store.isGranted(origin, DeviceConsentGroup.SENSORS) }
            compose.runOnIdle { showSettings.value = true }
            // Standalone settings section uses only isolated device prefs: no provider refresh or credentials.
            compose.waitUntil(5000) { compose.onAllNodesWithTag("device-revoke-sensors").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("device-revoke-sensors").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { !store.isGranted(origin, DeviceConsentGroup.SENSORS) }
            scope.launch { try { host.invoke("sensor.list", emptyMap()) } catch (_: DeviceCapabilityError) { } }
            compose.waitUntil(5000) { host.confirmation != null }
            compose.onNodeWithText("Deny").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { host.confirmation == null }
            assertFalse(store.isGranted(origin, DeviceConsentGroup.SENSORS))
        } finally { host.close(); scope.cancel(); context.deleteSharedPreferences(name) }
    }
}
