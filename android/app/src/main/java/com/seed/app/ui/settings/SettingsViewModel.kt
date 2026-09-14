package com.seed.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.seed.app.data.AndroidSettingsRepo
import com.seed.app.data.SettingsRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Settings tab.
 *
 * **Phase 5.6** shipped a local-only ViewModel —
 * the form lived in a [StateFlow] and [save]
 * recorded the current form into a `lastSaved`
 * flow so the screen could show "Saved" /
 * "Modified" feedback.
 *
 * **Phase 5.7** wired the form to a [SettingsRepo]
 * (DataStore + EncryptedSharedPreferences).
 *
 * Saving persists settings locally. They are injected into the runtime when
 * Seed is restarted; this prototype does not apply settings to a live runtime.
 *
 * The public API — [form], [lastSaved], the six
 * onXChange setters, and [save] — is the same
 * shape as Phase 5.6/5.7. The Compose screen
 * doesn't need to change.
 *
 */
class SettingsViewModel(
    private val repo: SettingsRepo = SettingsRepo.InMemory,
) : ViewModel() {

    private val _form = MutableStateFlow(SettingsForm.DEFAULTS)
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    private val _lastSaved = MutableStateFlow<SettingsForm?>(null)
    val lastSaved: StateFlow<SettingsForm?> = _lastSaved.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = repo.load()
            if (loaded != null) {
                _form.value = loaded
                _lastSaved.value = loaded
            }
        }
    }

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

    /** Save locally. Restart Seed to inject the new runtime credentials. */
    fun save() {
        viewModelScope.launch {
            val current = _form.value
            repo.save(current)
            _lastSaved.value = current
        }
    }

    companion object {
        /**
         * The default constructor takes [SettingsRepo.InMemory]
         * (a no-op) so the ViewModel is unit-testable on the
         * JVM without a `Context`. Production code uses
         * [Factory] (below) which wires in [AndroidSettingsRepo]
         * — the DataStore + EncryptedSharedPreferences-backed
         * impl. The screen sets `factory = Factory` in its
         * `viewModel()` call.
         *
         * **Why the `Context` comes from the factory and not
         * the constructor:** the constructor signature is
         * the test surface. A `Context` parameter would force
         * every test to construct or mock
         * one, which is heavy for what is otherwise a pure
         * data class. The factory is the production-only
         * wiring that knows how to get a `Context` from
         * Android (via the `APPLICATION_KEY` extra that
         * `viewModel()` populates from
         * `LocalContext.current`).
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("SettingsViewModel.Factory: APPLICATION_KEY missing from extras")
                SettingsViewModel(
                    repo = AndroidSettingsRepo(app as Context),
                )
            }
        }
    }
}
