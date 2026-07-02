package com.seed.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seed.app.R

/**
 * Settings tab — provider, model, API key, ports, log
 * level. Persisted via DataStore (Phase 5.7) and
 * encrypted separately for the API key.
 *
 * Phase 5.2 ships a placeholder. Phase 5.6 (Settings
 * UI) replaces it with a form. The form talks to a
 * `SettingsViewModel` that reads / writes the
 * `SettingsRepo` (Phase 5.7).
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_screen_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.settings_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true, name = "Settings tab placeholder")
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen()
    }
}
