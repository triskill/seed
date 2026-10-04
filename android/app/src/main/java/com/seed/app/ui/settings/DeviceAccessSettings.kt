package com.seed.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.seed.app.BuildConfig
import com.seed.app.R
import com.seed.app.device.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Separate from provider settings: revocation never saves credentials or calls a backend. */
@Composable
fun DeviceAccessSettings(
    consentStore: DeviceConsentStore? = null,
    origin: String = BuildConfig.WEBAPP_DEV_URL,
) {
    val context = LocalContext.current
    val store = consentStore ?: remember(context) { PreferencesDeviceConsentStore(context) }
    val canonicalOrigin = remember(origin) { canonicalDeviceOrigin(origin) }
    var grants by remember(store, canonicalOrigin) { mutableStateOf(emptySet<DeviceConsentGroup>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, canonicalOrigin) {
        store.prepare()
        grants = withContext(Dispatchers.IO) { DeviceConsentGroup.entries.filter { store.isGranted(canonicalOrigin, it) }.toSet() }
    }
    Column {
        Text(stringResource(R.string.device_access_title), style = MaterialTheme.typography.titleMedium)
        Text(canonicalOrigin, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.device_access_explanation), style = MaterialTheme.typography.bodySmall)
        DeviceConsentGroup.entries.forEach { group ->
            val label = stringResource(when (group) {
                DeviceConsentGroup.CAMERA -> R.string.device_group_camera
                DeviceConsentGroup.SENSORS -> R.string.device_group_sensors
                DeviceConsentGroup.LOCATION -> R.string.device_group_location
            })
            if (group in grants) {
                TextButton(
                    modifier = Modifier.semantics { testTag = "device-revoke-${group.id}" },
                    onClick = { scope.launch {
                        withContext(Dispatchers.IO) { store.revoke(canonicalOrigin, group) }
                        grants = grants - group
                    } },
                ) { Text(stringResource(R.string.device_revoke, label)) }
            } else {
                Text(stringResource(R.string.device_not_granted, label), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
