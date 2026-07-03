package com.seed.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.seed.app.data.AndroidSettingsRepo
import com.seed.app.data.ApiModule
import com.seed.app.data.ConfigSync
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
 * **Phase 6.5** adds a backend sync step:
 *   - the constructor now also takes a
 *     [ConfigSync] (default =
 *     `ConfigSync(ApiModule.default)`);
 *   - [save] now calls `repo.save(currentForm)`,
 *     then `sync.sync(currentForm)`, then
 *     updates [lastSaved]. The local save is
 *     authoritative; the backend sync is
 *     best-effort. A failed sync doesn't roll
 *     back the local save or block the
 *     "Modified" → "Saved" status pill flip —
 *     the user's settings round-trip on the
 *     next app start regardless. A future
 *     task may add a "Sync failed" banner.
 *
 * The public API — [form], [lastSaved], the six
 * onXChange setters, and [save] — is the same
 * shape as Phase 5.6/5.7. The Compose screen
 * doesn't need to change.
 *
 * **Why the default constructor builds a real
 * [ConfigSync] (which builds a real
 * [ApiModule.default] [BackendApi]):** the
 * Compose `viewModel<SettingsViewModel>()`
 * helper uses the no-arg overload, so
 * production code gets the real backend
 * client for free. Unit tests pass an
 * explicit [SettingsRepo] + [ConfigSync] fake
 * (see [com.seed.app.ui.settings.FakeConfigSync])
 * and never hit the default — so the
 * `ApiModule` reference in the default isn't
 * a test-time concern.
 */
class SettingsViewModel(
    private val repo: SettingsRepo = SettingsRepo.InMemory,
    private val sync: ConfigSync = ConfigSync(ApiModule.default),
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

    /**
     * Save the current form.
     *
     * **Phase 6.5:** the order is
     *   1. `repo.save(currentForm)` — local
     *      persistence (DataStore +
     *      EncryptedSharedPreferences).
     *   2. `sync.sync(currentForm)` — best-effort
     *      PUT to the backend's `/config` route.
     *      A failure is logged but doesn't roll
     *      back the local save (the local save
     *      is the authoritative state for the
     *      user's settings).
     *   3. Update [lastSaved] so the status pill
     *      flips "Modified" → "Saved".
     */
    fun save() {
        viewModelScope.launch {
            val current = _form.value
            repo.save(current)
            sync.sync(current)
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
         * every test (and every `RecordingSettingsRepo` /
         * `FakeConfigSync` call site) to construct or mock
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
                    sync = ConfigSync(ApiModule.default),
                )
            }
        }
    }
}
