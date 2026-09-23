package com.seed.app.ui.settings

import com.seed.app.data.AgentApplyRequest
import com.seed.app.data.AgentApplyResponse
import com.seed.app.data.BackendApi
import com.seed.app.data.HealthResponse
import com.seed.app.data.ModelsResponse
import com.seed.app.data.PiModelDto
import com.seed.app.data.ProviderModelsRequest
import com.seed.app.data.SelectionRequest
import com.seed.app.data.SelectionResponse
import com.seed.app.data.SettingsRepo
import com.seed.app.data.ShellExecRequest
import com.seed.app.data.ShellExecResponse
import com.seed.app.data.ThinkingLevelsResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Unit tests for local settings persistence and form state. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    // --- Phase 5.6 (still relevant) -------------------------------

    @Test
    fun initialStateHasDefaultsAndNullLastSaved() {
        val vm = SettingsViewModel()

        assertEquals(SettingsForm.DEFAULTS, vm.form.value)
        assertNull(vm.lastSaved.value)
    }

    @Test
    fun onProviderChangeClearsProviderSpecificModelAndKey() {
        val vm = SettingsViewModel()
        val before = vm.form.value
        vm.onProviderChange("anthropic")

        val after = vm.form.value
        assertEquals("anthropic", after.provider)
        assertEquals("", after.model)
        assertEquals("", after.apiKey)
        assertEquals(before.backendPort, after.backendPort)
        assertEquals(before.webappPort, after.webappPort)
        assertEquals(before.logLevel, after.logLevel)
    }

    @Test
    fun onModelChangeUpdatesModel() {
        val vm = SettingsViewModel()

        vm.onModelChange("claude-sonnet-4-5")

        assertEquals("claude-sonnet-4-5", vm.form.value.model)
    }

    @Test
    fun onApiKeyChangeUpdatesApiKey() {
        val vm = SettingsViewModel()

        vm.onApiKeyChange("sk-secret-12345")

        assertEquals("sk-secret-12345", vm.form.value.apiKey)
    }

    @Test
    fun onBackendPortChangeUpdatesBackendPort() {
        val vm = SettingsViewModel()

        vm.onBackendPortChange(8888)

        assertEquals(8888, vm.form.value.backendPort)
    }

    @Test
    fun onWebappPortChangeUpdatesWebappPort() {
        val vm = SettingsViewModel()

        vm.onWebappPortChange(9999)

        assertEquals(9999, vm.form.value.webappPort)
    }

    @Test
    fun onLogLevelChangeUpdatesLogLevel() {
        val vm = SettingsViewModel()

        vm.onLogLevelChange(LogLevel.DEBUG)

        assertEquals(LogLevel.DEBUG, vm.form.value.logLevel)
    }

    @Test
    fun multipleFieldChangesAccumulate() {
        val vm = SettingsViewModel()

        vm.onProviderChange("anthropic")
        vm.onModelChange("claude-sonnet-4-5")
        vm.onApiKeyChange("sk-test")
        vm.onBackendPortChange(8888)
        vm.onWebappPortChange(9999)
        vm.onLogLevelChange(LogLevel.WARNING)

        assertEquals(
            SettingsForm(
                provider = "anthropic",
                model = "claude-sonnet-4-5",
                apiKey = "sk-test",
                thinkingLevel = "off",
                backendPort = 8888,
                webappPort = 9999,
                logLevel = LogLevel.WARNING,
            ),
            vm.form.value,
        )
    }

    @Test
    fun saveRecordsCurrentFormAsLastSaved() {
        val vm = SettingsViewModel()
        vm.onProviderChange("anthropic")
        vm.onModelChange("claude-sonnet-4-5")
        vm.onApiKeyChange("sk-test")
        vm.onBackendPortChange(8888)

        vm.save()

        assertEquals(vm.form.value, vm.lastSaved.value)
    }

    @Test
    fun loginPersistsCredentialsWithoutRequiringAModelOrRestarting() {
        val repo = RecordingSettingsRepo()
        val vm = SettingsViewModel(repo = repo)
        vm.onProviderChange("opencode")
        vm.onApiKeyChange("key")

        vm.login()

        assertNull(repo.lastSaved)
        assertEquals("key", vm.form.value.apiKey)
        assertNull(vm.lastSaved.value)
        assertEquals("Could not save provider login", vm.saveError.value)
    }

    @Test
    fun successfulLoginRemovesLegacyKeyOnlyAfterBackendAcceptsCredential() {
        val repo = RecordingSettingsRepo(nextLoad = SettingsForm(provider = "anthropic", apiKey = "old-key"))
        val api = CatalogBackendApi()
        val vm = SettingsViewModel(repo = repo, api = api)
        assertEquals(true, vm.legacyKeyWarning.value)
        vm.onApiKeyChange("replacement")
        vm.login("anthropic")
        assertEquals(1, repo.clearLegacyCalls)
        assertEquals(false, vm.legacyKeyWarning.value)
    }

    @Test
    fun loggingIntoDifferentProviderPreservesOnlyLegacyCredential() {
        val repo = RecordingSettingsRepo(nextLoad = SettingsForm(provider = "anthropic", apiKey = "old-key"))
        val api = CatalogBackendApi()
        val vm = SettingsViewModel(repo = repo, api = api)
        vm.onProviderChange("openai")
        vm.onApiKeyChange("new-key")

        vm.login()

        assertEquals(0, repo.clearLegacyCalls)
        assertEquals(true, vm.legacyKeyWarning.value)
        assertEquals("", vm.form.value.apiKey)
        assertEquals("Login saved. Select a model, then save model settings.", vm.loginStatus.value)
    }

    @Test
    fun refreshConfigurationFindsShellAddedProvider() {
        val api = CatalogBackendApi()
        val vm = SettingsViewModel(api = api)
        api.extraProvider = "openai"
        vm.refreshConfiguration()
        assertEquals(true, "openai" in vm.configuredProviders.value)
    }

    @Test
    fun loginImmediatelyLoadsModelsForSavedProvider() {
        val api = CatalogBackendApi()
        val vm = SettingsViewModel(repo = RecordingSettingsRepo(), api = api)
        vm.onProviderChange("anthropic")
        vm.onApiKeyChange("sk-test")

        vm.login()

        assertEquals(3, api.modelCalls)
        assertEquals("anthropic", api.lastModelsRequest?.provider)
        assertEquals("", api.lastModelsRequest?.apiKey)
        assertEquals("", vm.form.value.apiKey)
        assertEquals("claude-test", vm.catalog.value.single().id)
    }

    @Test
    fun changingProviderDuringCatalogLoadFetchesNewProviderAndDiscardsOldResponse() {
        val api = CatalogBackendApi()
        api.extraProvider = "openai"
        val pending = CompletableDeferred<Unit>()
        api.beforeModelsReturn = { provider -> if (provider == "anthropic") pending.await() }
        val vm = SettingsViewModel(api = api)
        assertEquals(true, vm.catalogLoading.value)

        vm.onProviderChange("openai")
        pending.complete(Unit)

        assertEquals("openai", vm.form.value.provider)
        assertEquals("openai", api.lastModelsRequest?.provider)
        assertEquals("openai", vm.catalog.value.single().provider)
        assertEquals(false, vm.catalogLoading.value)
    }

    // --- Phase 5.7 (still relevant) -------------------------------

    @Test
    fun initHydratesFormAndLastSavedFromRepo() {
        val persisted = SettingsForm(
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            apiKey = "sk-persisted",
            backendPort = 8888,
            webappPort = 9999,
            logLevel = LogLevel.WARNING,
        )
        val repo = RecordingSettingsRepo(nextLoad = persisted)
        val vm = SettingsViewModel(repo = repo)

        assertEquals(persisted.copy(apiKey = ""), vm.form.value)
        assertEquals(persisted.copy(apiKey = ""), vm.lastSaved.value)
        assertEquals(true, vm.legacyKeyWarning.value)
    }

    @Test
    fun initLeavesLastSavedNullWhenRepoReturnsNull() {
        val repo = RecordingSettingsRepo(nextLoad = null)
        val vm = SettingsViewModel(repo = repo)

        assertEquals(SettingsForm.DEFAULTS, vm.form.value)
        assertNull(vm.lastSaved.value)
    }

    @Test
    fun saveCallsRepoSaveWithCurrentForm() {
        val repo = RecordingSettingsRepo(nextLoad = null)
        val vm = SettingsViewModel(repo = repo)
        vm.onProviderChange("anthropic")
        vm.onApiKeyChange("sk-new")

        vm.save()

        assertEquals(vm.form.value.copy(apiKey = ""), repo.lastSaved)
    }

    @Test
    fun saveUpdatesLastSavedButLeavesFormUnchanged() {
        val repo = RecordingSettingsRepo(nextLoad = null)
        val vm = SettingsViewModel(repo = repo)
        vm.onModelChange("claude-sonnet-4-5")

        val formBeforeSave = vm.form.value
        assertNull(vm.lastSaved.value) // not saved yet

        vm.save()

        assertEquals(formBeforeSave, vm.form.value) // unchanged
        assertEquals(formBeforeSave, vm.lastSaved.value) // now saved
    }

    @Test
    fun inMemoryRepoBehavesLikePhase56() {
        val vm = SettingsViewModel() // default = InMemory

        assertEquals(SettingsForm.DEFAULTS, vm.form.value)
        assertNull(vm.lastSaved.value)

        vm.onModelChange("gpt-4o")
        vm.save()

        assertEquals(vm.form.value, vm.lastSaved.value)
    }
}

private class CatalogBackendApi : BackendApi {
    var extraProvider: String? = null
    var modelCalls = 0
    var lastModelsRequest: ProviderModelsRequest? = null
    var beforeModelsReturn: suspend (String) -> Unit = {}

    override suspend fun health() = HealthResponse("ok", "up")
    override suspend fun shellExec(request: ShellExecRequest, authorization: String) =
        ShellExecResponse("", "", 0)

    override suspend fun config(authorization: String) = com.seed.app.data.PiConfigResponse(providers = listOf("anthropic") + listOfNotNull(extraProvider))
    override suspend fun addProvider(request: ProviderModelsRequest, authorization: String) = com.seed.app.data.PiConfigResponse(providers = listOf(request.provider))
    override suspend fun models(
        provider: String,
        authorization: String,
    ): ModelsResponse {
        modelCalls += 1
        lastModelsRequest = ProviderModelsRequest(provider, "")
        beforeModelsReturn(provider)
        return ModelsResponse(
            listOf(
                PiModelDto(
                    provider = provider,
                    id = "claude-test",
                    name = "Claude Test",
                ),
            ),
        )
    }

    override suspend fun thinkingLevels(
        provider: String,
        modelId: String,
        authorization: String,
    ) = ThinkingLevelsResponse(provider, modelId, listOf("off"))

    override suspend fun validateSelection(
        request: SelectionRequest,
        authorization: String,
    ) = SelectionResponse(valid = true)

    override suspend fun applyAgents(
        request: AgentApplyRequest,
        authorization: String,
    ) = AgentApplyResponse(applied = true)
}

/**
 * Test fake for [SettingsRepo]. Scriptable via
 * [nextLoad] and observable via [lastSaved] / [saveCalls].
 * Not thread-safe — tests are single-threaded under
 * `runTest`.
 */
private class RecordingSettingsRepo(
    private var nextLoad: SettingsForm? = null,
) : SettingsRepo {
    var clearLegacyCalls = 0
    override suspend fun clearLegacyCredential() { clearLegacyCalls++ }
    var lastSaved: SettingsForm? = null
        private set
    var saveCalls: Int = 0
        private set

    override suspend fun load(): SettingsForm? = nextLoad

    override suspend fun save(form: SettingsForm) {
        lastSaved = form
        saveCalls += 1
        // Mirror the persistence into nextLoad so a
        // subsequent load() would return the saved
        // form (this matches how a real disk-backed
        // repo behaves after a save).
        nextLoad = form
    }
}
