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
import com.seed.app.data.ProviderModelsRequest
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
 * stores a credential without interrupting the running app, then loads that
 * provider's Pi model catalog. Saving replaces only the two Pi chat
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

    private val _configuredProviders = MutableStateFlow<List<String>>(emptyList())
    val configuredProviders: StateFlow<List<String>> = _configuredProviders.asStateFlow()
    private val _legacyKeyWarning = MutableStateFlow(false)
    val legacyKeyWarning: StateFlow<Boolean> = _legacyKeyWarning.asStateFlow()
    private var legacyCredentialProvider: String? = null

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
                if (loaded.apiKey.isNotBlank()) {
                    legacyCredentialProvider = loaded.provider
                    _legacyKeyWarning.value = true
                }
                _form.value = loaded.copy(apiKey = "")
                _lastSaved.value = loaded.copy(apiKey = "")
            }
            if (api != null) {
                try {
                    val config = api.config("Bearer ${RuntimeService.controlCapability}")
                    _configuredProviders.value = config.providers
                    val provider = config.defaultProvider ?: config.providers.firstOrNull().orEmpty()
                    _form.update { it.copy(provider = provider, model = config.defaultModel.orEmpty(), thinkingLevel = config.defaultThinkingLevel ?: "off") }
                    if (provider.isNotBlank()) loadCatalog()
                } catch (_: Exception) {
                    _catalogError.value = "Could not load Pi configuration. Retry."
                }
            }
        }
    }

    /** Re-query Pi when Settings is entered; shell edits may have changed providers. */
    fun refreshConfiguration() {
        val service = api ?: return
        viewModelScope.launch {
            try {
                val config = service.config("Bearer ${RuntimeService.controlCapability}")
                _configuredProviders.value = config.providers
                if (_form.value.provider in config.providers) loadCatalog()
            } catch (_: Exception) {
                _catalogError.value = "Could not load Pi configuration. Retry."
            }
        }
    }

    fun onProviderChange(value: String) {
        // Clear the previous provider's models; this provider's catalog is
        // loaded after its API key is saved.
        _catalog.value = emptyList()
        _catalogError.value = null
        _loginStatus.value = null
        _form.update { it.copy(provider = value, model = "", apiKey = "", thinkingLevel = "off") }
        if (value in _configuredProviders.value) loadCatalog()
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
        if (provider !in _configuredProviders.value) {
            _catalog.value = emptyList()
            _catalogError.value = null
            return
        }
        viewModelScope.launch {
            _catalogLoading.value = true
            _catalogError.value = null
            try {
                // Flask can report healthy just before the separate Pi control
                // process has completed its RPC handshake. Retry that bounded
                // startup window instead of leaving Settings permanently empty.
                repeat(CATALOG_ATTEMPTS) { attempt ->
                    try {
                        val response = service.models(provider, "Bearer ${RuntimeService.controlCapability}")
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
                if (_form.value.provider == provider) {
                    _catalogError.value = "Could not load models. Check login and retry."
                }
            } finally {
                _catalogLoading.value = false
                // A provider change during this request could not start its own
                // load while the loading guard was set. Fetch it now instead.
                if (_form.value.provider != provider) loadCatalog()
            }
        }
    }

    /** Save a provider credential without disrupting the currently running app. */
    fun login(provider: String = _form.value.provider) {
        if (_applying.value) return
        viewModelScope.launch {
            _applying.value = true
            _saveError.value = null
            try {
                val credentials = _form.value.copy(provider = provider, model = "", thinkingLevel = "off")
                check(credentials.apiKey.isNotBlank()) { "Enter an API key before logging in" }
                val service = api ?: error("Pi backend unavailable")
                service.addProvider(ProviderModelsRequest(credentials.provider, credentials.apiKey), "Bearer ${RuntimeService.controlCapability}")
                if (legacyCredentialProvider == credentials.provider) {
                    repo.clearLegacyCredential()
                    legacyCredentialProvider = null
                    _legacyKeyWarning.value = false
                }
                _configuredProviders.value = service.config("Bearer ${RuntimeService.controlCapability}").providers
                _form.value = credentials.copy(apiKey = "")
                _lastSaved.value = _form.value
                _loginStatus.value = "Login saved. Select a model, then save model settings."
                loadCatalog()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _saveError.value = "Could not save provider login"
            } finally {
                _applying.value = false
            }
        }
    }

    /** Validate and apply Pi agents before persisting local non-secret preferences. */
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
                    check(current.provider in _configuredProviders.value && option != null) { "Choose a configured provider and catalog model" }
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
                if (api != null) {
                    val applied = api.applyAgents(
                        AgentApplyRequest(
                            provider = current.provider,
                            modelId = current.model,
                            thinkingLevel = current.thinkingLevel,
                        ),
                        "Bearer ${RuntimeService.controlCapability}",
                    )
                    check(applied.applied) { "Pi agents rejected settings" }
                }
                val saved = if (api != null) {
                    try {
                        val config = api.config("Bearer ${RuntimeService.controlCapability}")
                        _configuredProviders.value = config.providers
                        current.copy(
                            provider = config.defaultProvider ?: current.provider,
                            model = config.defaultModel ?: current.model,
                            thinkingLevel = config.defaultThinkingLevel ?: current.thinkingLevel,
                            apiKey = "",
                        )
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        // Apply succeeded; inability to refresh must not be reported as apply failure.
                        _saveError.value = "Pi settings applied, but configuration could not be refreshed"
                        current.copy(apiKey = "")
                    }
                } else current.copy(apiKey = "")
                if (api != null) _form.value = saved
                try {
                    repo.save(saved)
                    _lastSaved.value = if (api == null) current else saved
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    _saveError.value = "Pi settings applied, but local preferences could not be saved"
                }
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
