package com.seed.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * no `SettingsRepo`. The form lived in a
 * [StateFlow] and [save] recorded the current
 * form into a `lastSaved` flow so the screen
 * could show "Saved" / "Modified" feedback.
 *
 * **Phase 5.7** wires the form to a [SettingsRepo]:
 *   - the constructor now takes a `SettingsRepo`
 *     (default = [SettingsRepo.InMemory] for tests,
 *     previews, and the no-arg convenience
 *     overload);
 *   - on init, the ViewModel launches a coroutine
 *     that calls `repo.load()` and, if a form is
 *     persisted, hydrates both the in-memory form
 *     and the `lastSaved` flow from it (so the
 *     "Saved" status pill is correct after a
 *     restart);
 *   - [save] now calls `repo.save(currentForm)`
 *     and updates `lastSaved`.
 *
 * The public API — [form], [lastSaved], the six
 * onXChange setters, and [save] — is the same
 * shape as Phase 5.6. The screen doesn't need to
 * change. (See `SettingsScreen.kt` for the
 * Compose side.)
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
class SettingsViewModel(
    private val repo: SettingsRepo = SettingsRepo.InMemory,
) : ViewModel() {

    private val _form = MutableStateFlow(SettingsForm.DEFAULTS)
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    /**
     * The most recently saved form, or `null` if
     * nothing has been saved yet (fresh install,
     * or a restart with no persisted form). The
     * screen compares this to [form] to render
     * "Modified" (form != lastSaved) vs. "Saved"
     * (form == lastSaved).
     *
     * **Phase 5.7:** this is now hydrated from
     * `repo.load()` on construction. A
     * [SettingsRepo.load] that returns `null`
     * leaves `lastSaved` at `null` (we don't
     * treat the in-memory defaults as "saved" —
     * the user has to tap Save at least once).
     */
    private val _lastSaved = MutableStateFlow<SettingsForm?>(null)
    val lastSaved: StateFlow<SettingsForm?> = _lastSaved.asStateFlow()

    init {
        // Fire-and-forget hydration from disk. The
        // form starts at DEFAULTS; once the disk
        // read completes, it gets overwritten with
        // the persisted form (if any). We use
        // viewModelScope so the coroutine is
        // cancelled if the ViewModel is cleared
        // before the read finishes.
        //
        // This must NOT be a one-time `runBlocking`
        // — the production impl reads from
        // DataStore, which is async-only, and
        // blocking the main thread on disk I/O
        // would jank app startup.
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
     * Save the current form. Phase 5.6 recorded
     * the form in-memory; Phase 5.7 calls
     * [SettingsRepo.save] on the injected repo
     * (default = [SettingsRepo.InMemory] which
     * no-ops) and updates [lastSaved] so the
     * "Modified" → "Saved" status pill flips.
     */
    fun save() {
        viewModelScope.launch {
            val current = _form.value
            repo.save(current)
            _lastSaved.value = current
        }
    }
}
