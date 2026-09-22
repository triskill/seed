package com.seed.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.seed.app.data.AgentApplyRequest
import com.seed.app.data.AndroidSettingsRepo
import com.seed.app.data.ApiModule
import com.seed.app.data.BackendApi
import com.seed.app.data.ModelOption
import com.seed.app.data.SettingsRepo
import com.seed.app.runtime.RuntimeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * Provider login and model configuration are intentionally separate: Login
 * stores a credential without interrupting the running app, and the generic
 * Pi catalog is used to select a model. Saving replaces only the two Pi chat
 * agents; FastAPI, Flask, and PRoot keep running. Neither action gates startup.
 *
 * The public API — [form], [lastSaved], the six
 * onXChange setters, and [save] — is the same
 * shape as Phase 5.6/5.7. The Compose screen
 * doesn't need to change.
 *
 */
class SettingsViewModel(
    private val repo: SettingsRepo = SettingsRepo.InMemory,
    private val api: BackendApi? = null,
) : ViewModel() {

    private val _form = MutableStateFlow(SettingsForm.DEFAULTS)
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    private val _lastSaved = MutableStateFlow<SettingsForm?>(null)
    val lastSaved: StateFlow<SettingsForm?> = _lastSaved.asStateFlow()

    private val _catalog = MutableStateFlow<List<ModelOption>>(emptyList())
    val catalog: StateFlow<List<ModelOption>> = _catalog.asStateFlow()
    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()
    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()
    private val _loginStatus = MutableStateFlow<String?>(null)
    val loginStatus: StateFlow<String?> = _loginStatus.asStateFlow()
    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = repo.load()
            if (loaded != null) {
                _form.value = loaded
                _lastSaved.value = loaded
            }
            if (api != null) loadCatalog()
        }
    }

    fun onProviderChange(value: String) {
        // The generic catalog comes from the running control Pi and is not
        // credential-gated, so it can be queried before storing a login.
        _catalog.value = emptyList()
        _catalogError.value = null
        _loginStatus.value = null
        _form.update { it.copy(provider = value, model = "", apiKey = "", thinkingLevel = "off") }
        // Pi's model registry is not credential-gated; use the running control
        // process to populate selection immediately, before saving a login.
        loadCatalog()
    }

    fun onModelChange(value: String) {
        val option = _catalog.value.firstOrNull { it.provider == _form.value.provider && it.id == value }
        _form.update { it.copy(model = value, thinkingLevel = option?.thinkingLevels?.firstOrNull() ?: "off") }
    }

    fun onModelChange(option: ModelOption) {
        if (option.provider != _form.value.provider) return
        _form.update { it.copy(model = option.id, thinkingLevel = option.thinkingLevels.firstOrNull() ?: "off") }
    }

    fun onThinkingLevelChange(value: String) {
        if (value in (_catalog.value.firstOrNull { it.provider == _form.value.provider && it.id == _form.value.model }?.thinkingLevels ?: emptyList())) {
            _form.update { it.copy(thinkingLevel = value) }
        }
    }

    fun onApiKeyChange(value: String) {
        _loginStatus.value = null
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

    fun loadCatalog() {
        val service = api ?: return
        if (_catalogLoading.value) return
        val provider = _form.value.provider
        viewModelScope.launch {
            _catalogLoading.value = true
            _catalogError.value = null
            try {
                // Flask can report healthy just before the separate Pi control
                // process has completed its RPC handshake. Retry that bounded
                // startup window instead of leaving Settings permanently empty.
                repeat(CATALOG_ATTEMPTS) { attempt ->
                    try {
                        val response = service.models("Bearer ${RuntimeService.controlCapability}")
                        // Discard a response for a provider the user changed
                        // while this request was in flight.
                        if (_form.value.provider != provider) return@launch
                        _catalog.value = response.models.map { it.toDomain() }
                            .filter { it.provider == provider }
                        return@launch
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        if (attempt < CATALOG_ATTEMPTS - 1) delay(CATALOG_RETRY_DELAY_MS)
                    }
                }
                _catalogError.value = "Could not load models. Check login and retry."
            } finally {
                _catalogLoading.value = false
            }
        }
    }

    /** Save a provider credential without disrupting the currently running app. */
    fun login() {
        if (_applying.value) return
        viewModelScope.launch {
            _applying.value = true
            _saveError.value = null
            try {
                val credentials = _form.value.copy(model = "", thinkingLevel = "off")
                check(credentials.apiKey.isNotBlank()) { "Enter an API key before logging in" }
                repo.save(credentials)
                _form.value = credentials
                _lastSaved.value = credentials
                _loginStatus.value = "Login saved. Select a model, then save model settings."
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _saveError.value = "Could not save provider login"
            } finally {
                _applying.value = false
            }
        }
    }

    /** Validate, persist, and replace only the two Pi chat agents. */
    fun save() {
        if (_applying.value) return
        viewModelScope.launch {
            val current = _form.value
            _applying.value = true
            _saveError.value = null
            try {
                val option = _catalog.value.firstOrNull {
                    it.provider == current.provider && it.id == current.model
                }
                if (api != null) {
                    check(option != null) { "Choose a model from the Pi catalog" }
                    val response = api.validateSelection(
                        com.seed.app.data.SelectionRequest(
                            current.provider,
                            current.model,
                            current.thinkingLevel,
                        ),
                        "Bearer ${RuntimeService.controlCapability}",
                    )
                    check(response.valid) { "Pi rejected this selection" }
                }
                repo.save(current)
                if (api != null) {
                    val applied = api.applyAgents(
                        AgentApplyRequest(
                            provider = current.provider,
                            modelId = current.model,
                            thinkingLevel = current.thinkingLevel,
                            apiKey = current.apiKey,
                        ),
                        "Bearer ${RuntimeService.controlCapability}",
                    )
                    check(applied.applied) { "Pi agents rejected settings" }
                }
                _lastSaved.value = current
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _saveError.value = "Could not validate or apply settings"
            } finally {
                _applying.value = false
            }
        }
    }

    companion object {
        private const val CATALOG_ATTEMPTS = 8
        private const val CATALOG_RETRY_DELAY_MS = 750L

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
                    api = ApiModule.embedded,
                )
            }
        }
    }
}
