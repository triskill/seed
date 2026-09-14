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
import androidx.compose.material3.TextButton
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
import com.seed.app.data.ModelOption
import com.seed.app.data.ProviderCatalog

/**
 * Settings tab — provider, model, API key, ports, log
 * level.
 *
 * Phase 5.6 replaces the 5.2 placeholder with a
 * scrollable form bound to a `SettingsViewModel`:
 *   - a [SettingsHeader] (title + "Modified" /
 *     "Saved" status pill);
 *   - a closed-set `ProviderDropdown` backed by the
 *     curated provider catalog;
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
 * Settings persist through DataStore and encrypted
 * preferences. Saving restarts the runtime so a provider/key change applies
 * to the Pi processes; it never redirects the user out of the app.
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
    onApplied: () -> Unit = {},
) {
    val form by viewModel.form.collectAsState()
    val lastSaved by viewModel.lastSaved.collectAsState()
    val isSaved = lastSaved != null && lastSaved == form
    val catalog by viewModel.catalog.collectAsState()
    val catalogLoading by viewModel.catalogLoading.collectAsState()
    val catalogError by viewModel.catalogError.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val loginStatus by viewModel.loginStatus.collectAsState()
    val applying by viewModel.applying.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(isSaved = isSaved)

        HorizontalDivider()

        Text(
            text = stringResource(R.string.settings_section_login),
            style = MaterialTheme.typography.titleMedium,
        )
        ProviderDropdown(
            value = form.provider,
            onValueChange = viewModel::onProviderChange,
        )
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text(stringResource(R.string.settings_field_api_key)) },
            singleLine = true,
            // Hide the key as the user types so a bystander cannot read it.
            // Android stores the at-rest copy in Keystore-backed encrypted
            // preferences.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTag = "settings-field-api-key" },
        )
        Button(
            onClick = viewModel::login,
            enabled = !applying,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "settings-login" },
        ) {
            Text(stringResource(R.string.settings_action_login))
        }

        loginStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.settings_section_model),
            style = MaterialTheme.typography.titleMedium,
        )
        ModelDropdown(
            value = form.model,
            models = catalog,
            loading = catalogLoading,
            onValueChange = viewModel::onModelChange,
        )
        catalogError?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::loadCatalog) { Text(stringResource(R.string.settings_action_retry)) }
            }
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val selectedModel = catalog.firstOrNull { it.provider == form.provider && it.id == form.model }
        if (selectedModel != null && selectedModel.thinkingLevels.isNotEmpty()) {
            ThinkingLevelDropdown(
                value = form.thinkingLevel,
                levels = selectedModel.thinkingLevels,
                onValueChange = viewModel::onThinkingLevelChange,
            )
        }

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
            onClick = { viewModel.save(onApplied) },
            enabled = !applying,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTag = "settings-save" },
        ) {
            Text(stringResource(R.string.settings_action_save_model))
        }
        Text(
            text = stringResource(R.string.settings_restart_required),
            style = MaterialTheme.typography.bodySmall,
        )
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
 * list is the closed-set [ProviderCatalog] used by
 * Settings. Unknown provider identifiers cannot be persisted or passed to Pi.
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
        modifier = Modifier.fillMaxWidth().semantics { testTag = "settings-field-provider" },
    ) {
        TextField(
            value = ProviderCatalog.find(value)?.displayName ?: value,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_field_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProviderCatalog.PROVIDERS.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = { onValueChange(provider.id); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    value: String,
    models: List<ModelOption>,
    loading: Boolean,
    onValueChange: (ModelOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(value) { mutableStateOf(value) }
    val selected = models.firstOrNull { it.id == value }
    val filtered = models.filter { query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().semantics { testTag = "settings-field-model" },
    ) {
        TextField(
            value = if (expanded) query else (selected?.name ?: value),
            onValueChange = { query = it; expanded = true },
            readOnly = loading,
            label = { Text(stringResource(R.string.settings_field_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filtered.forEach { model ->
                DropdownMenuItem(
                    text = { Text("${model.name} (${model.id})") },
                    onClick = { onValueChange(model); query = model.id; expanded = false },
                )
            }
            if (filtered.isEmpty()) DropdownMenuItem(text = { Text("No catalog models") }, onClick = { expanded = false })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelDropdown(
    value: String,
    levels: List<String>,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("Thinking level") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth().semantics { testTag = "settings-field-thinking" },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            levels.forEach { level ->
                DropdownMenuItem(text = { Text(level) }, onClick = { onValueChange(level); expanded = false })
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
