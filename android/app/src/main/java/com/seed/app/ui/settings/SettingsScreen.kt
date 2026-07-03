package com.seed.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seed.app.R
import com.seed.app.data.AndroidSettingsRepo
import com.seed.app.data.ApiModule
import com.seed.app.data.ConfigSync
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Settings tab — provider, model, API key, ports, log
 * level.
 *
 * Phase 5.6 replaces the 5.2 placeholder with a
 * scrollable form bound to a `SettingsViewModel`:
 *   - a [SettingsHeader] (title + "Modified" /
 *     "Saved" status pill);
 *   - a `ProviderDropdown` (the user picks from
 *     [SettingsForm.KNOWN_PROVIDERS] or types a
 *     free-form value);
 *   - a model `OutlinedTextField`;
 *   - an API-key `OutlinedTextField` with
 *     [PasswordVisualTransformation] so the key
 *     doesn't render in plain text on screen;
 *   - two port-number `OutlinedTextField`s with
 *     `KeyboardType.Number` (the screen parses
 *     each to `Int`; invalid input silently
 *     collapses to `0` rather than throwing);
 *   - a `LogLevelDropdown` (one of [LogLevel]);
 *   - a Save button that calls
 *     [SettingsViewModel.save].
 *
 * **No persistence yet.** `SettingsViewModel.save`
 * just records the current form in an in-memory
 * `lastSaved` flow (so the screen can show "Saved"
 * / "Modified" feedback). Phase 5.7 will add a
 * `SettingsRepo` (DataStore-Preferences for the
 * non-secret fields + EncryptedSharedPreferences
 * for the API key) and rewrite `save` to persist.
 * The public ViewModel API
 * ([SettingsViewModel.form],
 * [SettingsViewModel.lastSaved], and the six
 * onXChange setters) is stable.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory,
    ),
) {
    val form by viewModel.form.collectAsState()
    val lastSaved by viewModel.lastSaved.collectAsState()
    val isSaved = lastSaved != null && lastSaved == form

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(isSaved = isSaved)

        HorizontalDivider()

        ProviderDropdown(
            value = form.provider,
            onValueChange = viewModel::onProviderChange,
        )

        OutlinedTextField(
            value = form.model,
            onValueChange = viewModel::onModelChange,
            label = { Text(stringResource(R.string.settings_field_model)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTag = "settings-field-model" },
        )

        OutlinedTextField(
            value = form.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text(stringResource(R.string.settings_field_api_key)) },
            singleLine = true,
            // Hide the key as the user types so a
            // bystander can't read it off the
            // screen. Phase 5.7 also stores the key
            // in EncryptedSharedPreferences (which
            // lives in the Android keystore) so
            // the at-rest copy is also protected.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTag = "settings-field-api-key" },
        )

        // The two port fields sit on one row so the
        // form stays compact on a phone screen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PortField(
                label = stringResource(R.string.settings_field_backend_port),
                value = form.backendPort,
                onValueChange = viewModel::onBackendPortChange,
                testTag = "settings-field-backend-port",
                modifier = Modifier.weight(1f),
            )
            PortField(
                label = stringResource(R.string.settings_field_webapp_port),
                value = form.webappPort,
                onValueChange = viewModel::onWebappPortChange,
                testTag = "settings-field-webapp-port",
                modifier = Modifier.weight(1f),
            )
        }

        LogLevelDropdown(
            value = form.logLevel,
            onValueChange = viewModel::onLogLevelChange,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = viewModel::save,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTag = "settings-save" },
        ) {
            Text(stringResource(R.string.settings_action_save))
        }
    }
}

/**
 * Title + status pill. Lives at the top of the
 * scrollable area (not a Scaffold topAppBar) so the
 * whole form scrolls together on small screens.
 */
@Composable
private fun SettingsHeader(isSaved: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.settings_screen_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        StatusPill(isSaved = isSaved)
    }
}

/**
 * A small "Modified" / "Saved" indicator on the
 * right side of the header. Uses a tonal
 * [Surface] so it reads as a chip without
 * pulling in the M3 `AssistChip` (which is in
 * a separate artifact and not needed for one
 * static label).
 */
@Composable
private fun StatusPill(isSaved: Boolean) {
    val labelRes = if (isSaved) {
        R.string.settings_status_saved
    } else {
        R.string.settings_status_modified
    }
    val container = if (isSaved) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (isSaved) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Numeric `OutlinedTextField` for a port number.
 * Backs onto an `Int` in the form so the data
 * class stays typed; the text field is just a
 * thin `String <-> Int` adapter (parse to Int
 * on each keystroke, falling back to `0` for
 * invalid input so the text field never throws
 * mid-typing).
 */
@Composable
private fun PortField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toString(),
        // `toIntOrNull() ?: 0` collapses mid-typed
        // garbage (e.g. just-typed "1" with a
        // trailing space) to 0, which the user
        // will overwrite on the next keystroke.
        onValueChange = { text ->
            onValueChange(text.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.semantics { this.testTag = testTag },
    )
}

/**
 * M3 exposed-dropdown for the provider name. The
 * list is [SettingsForm.KNOWN_PROVIDERS] but the
 * field is still free-form: a value not in the
 * list is kept as-is (some users will type their
 * own provider URL). The dropdown only suggests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "settings-field-provider" },
    ) {
        // Use the un-styled `TextField` (not
        // `OutlinedTextField`) here because
        // `ExposedDropdownMenuBox` provides its own
        // outlined chrome via the `menuAnchor`
        // modifier on this child.
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.settings_field_provider)) },
            readOnly = false,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SettingsForm.KNOWN_PROVIDERS.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider) },
                    onClick = {
                        onValueChange(provider)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * M3 exposed-dropdown for the log level. Unlike
 * the provider dropdown this is closed-set
 * ([LogLevel] enum), so the user can't type a
 * value that's not in the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogLevelDropdown(
    value: LogLevel,
    onValueChange: (LogLevel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "settings-field-log-level" },
    ) {
        TextField(
            value = value.displayName,
            onValueChange = {},
            label = { Text(stringResource(R.string.settings_field_log_level)) },
            readOnly = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LogLevel.values().forEach { level ->
                DropdownMenuItem(
                    text = { Text(level.displayName) },
                    onClick = {
                        onValueChange(level)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Settings tab empty")
@Composable
private fun SettingsScreenEmptyPreview() {
    MaterialTheme {
        SettingsScreen()
    }
}
