package com.seed.app.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Drives the Settings tab.
 *
 * **Phase 5.6** ships a local-only ViewModel —
 * no DataStore, no `SettingsRepo`. The form lives
 * in a [StateFlow] and [save] records the current
 * form into a `lastSaved` flow so the screen can
 * show "Saved" / "Modified" feedback.
 *
 * **Phase 5.7** (Task 5.7, "DataStore for
 * settings") will add a `SettingsRepo`
 * (DataStore-Preferences for the non-secret fields
 * + EncryptedSharedPreferences for the API key,
 * which lives in the Android keystore). The
 * ViewModel's public API is the same — Phase 5.7
 * only rewires [save] to call the repo, and adds
 * a constructor-injected `SettingsRepo` parameter.
 *
 * **Why two flows, not one:** the form updates on
 * every keystroke / dropdown pick, and `lastSaved`
 * updates only on explicit Save. Splitting them
 * means the screen can render "Modified" vs.
 * "Saved" without an extra `equals` check.
 *
 * **Why setters per field, not one
 * `onFieldChange(K, V)`:** each setter is a single
 * `copy(...)` call and the typed signature is a
 * compile-time check that the field name and the
 * value type match (can't pass a `LogLevel` to
 * `onModelChange`).
 */
class SettingsViewModel : ViewModel() {

    private val _form = MutableStateFlow(SettingsForm.DEFAULTS)
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    /**
     * The most recently saved form, or `null` if
     * the user hasn't tapped Save yet. The screen
     * compares this to [form] to render
     * "Modified" (form != lastSaved) vs. "Saved"
     * (form == lastSaved).
     *
     * **Phase 5.6 only:** this is in-memory. Phase
     * 5.7 will hydrate [_form] from the DataStore
     * repo on construction and persist on [save].
     */
    private val _lastSaved = MutableStateFlow<SettingsForm?>(null)
    val lastSaved: StateFlow<SettingsForm?> = _lastSaved.asStateFlow()

    fun onProviderChange(value: String) {
        _form.update { it.copy(provider = value) }
    }

    fun onModelChange(value: String) {
        _form.update { it.copy(model = value) }
    }

    fun onApiKeyChange(value: String) {
        _form.update { it.copy(apiKey = value) }
    }

    fun onBackendPortChange(value: Int) {
        _form.update { it.copy(backendPort = value) }
    }

    fun onWebappPortChange(value: Int) {
        _form.update { it.copy(webappPort = value) }
    }

    fun onLogLevelChange(value: LogLevel) {
        _form.update { it.copy(logLevel = value) }
    }

    /**
     * Save the current form. Phase 5.6 records
     * the form in-memory; Phase 5.7 will persist
     * to DataStore (and the API key to
     * EncryptedSharedPreferences) and return a
     * `Job` / suspend so the screen can show a
     * progress indicator.
     */
    fun save() {
        _lastSaved.value = _form.value
    }
}
