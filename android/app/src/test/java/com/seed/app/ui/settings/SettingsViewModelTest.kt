package com.seed.app.ui.settings

import com.seed.app.data.ConfigRequest
import com.seed.app.data.ConfigSync
import com.seed.app.data.SettingsRepo
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

/**
 * Unit tests for [SettingsViewModel].
 *
 * Phase 5.7 adds a [SettingsRepo] parameter to the
 * ViewModel constructor. Phase 6.5 adds a
 * [ConfigSync] parameter (best-effort PUT to the
 * backend's `/config` route).
 *
 * The wiring tests below use two small in-test
 * fakes:
 *   - [RecordingSettingsRepo] — scriptable via
 *     [RecordingSettingsRepo.nextLoad], observable
 *     via [RecordingSettingsRepo.lastSaved] and
 *     [RecordingSettingsRepo.saveCalls];
 *   - [FakeConfigSync] — captures outbound
 *     [ConfigRequest]s into [FakeConfigSync.requests]
 *     and a configurable
 *     [FakeConfigSync.nextResult] (default = `true`)
 *     so tests can simulate a sync failure.
 *
 * **Why both fakes (not a single combined one):**
 * the repo and the sync are separate concerns
 * (local persistence vs. backend round-trip) and
 * the production constructor takes them as
 * separate parameters. Mirroring the production
 * shape in the tests makes the dependency
 * injection clear and lets a test focus on one
 * seam (e.g. the "sync failed" case in
 * [saveUpdatesLastSavedEvenIfSyncFails] doesn't
 * need to care about the repo's behaviour).
 *
 * **Why a Main dispatcher setup:** the
 * ViewModel's `viewModelScope` uses
 * `Dispatchers.Main.immediate`, which on the JVM
 * has no default and throws
 * `IllegalStateException("Module with the
 * Main dispatcher had failed to initialize")` if
 * it isn't installed. We install an
 * [UnconfinedTestDispatcher] in [setUp] (which
 * runs coroutines eagerly, so we don't need
 * `advanceUntilIdle()`) and reset it in [tearDown].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var fakeSync: FakeConfigSync

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Default-fake with a happy-path result.
        // Tests that want a different outcome
        // override `fakeSync.nextResult` after
        // creating the VM (or create a new fake
        // via the explicit `SettingsViewModel(...)`
        // constructor).
        fakeSync = FakeConfigSync(nextResult = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    // --- Phase 5.6 (still relevant) -------------------------------

    @Test
    fun initialStateHasDefaultsAndNullLastSaved() {
        val vm = SettingsViewModel(sync = fakeSync)

        assertEquals(SettingsForm.DEFAULTS, vm.form.value)
        assertNull(vm.lastSaved.value)
    }

    @Test
    fun onProviderChangeUpdatesProviderAndLeavesOtherFieldsUnchanged() {
        val vm = SettingsViewModel(sync = fakeSync)
        val before = vm.form.value

        vm.onProviderChange("anthropic")

        val after = vm.form.value
        assertEquals("anthropic", after.provider)
        assertEquals(before.model, after.model)
        assertEquals(before.apiKey, after.apiKey)
        assertEquals(before.backendPort, after.backendPort)
        assertEquals(before.webappPort, after.webappPort)
        assertEquals(before.logLevel, after.logLevel)
    }

    @Test
    fun onModelChangeUpdatesModel() {
        val vm = SettingsViewModel(sync = fakeSync)

        vm.onModelChange("claude-sonnet-4-5")

        assertEquals("claude-sonnet-4-5", vm.form.value.model)
    }

    @Test
    fun onApiKeyChangeUpdatesApiKey() {
        val vm = SettingsViewModel(sync = fakeSync)

        vm.onApiKeyChange("sk-secret-12345")

        assertEquals("sk-secret-12345", vm.form.value.apiKey)
    }

    @Test
    fun onBackendPortChangeUpdatesBackendPort() {
        val vm = SettingsViewModel(sync = fakeSync)

        vm.onBackendPortChange(8888)

        assertEquals(8888, vm.form.value.backendPort)
    }

    @Test
    fun onWebappPortChangeUpdatesWebappPort() {
        val vm = SettingsViewModel(sync = fakeSync)

        vm.onWebappPortChange(9999)

        assertEquals(9999, vm.form.value.webappPort)
    }

    @Test
    fun onLogLevelChangeUpdatesLogLevel() {
        val vm = SettingsViewModel(sync = fakeSync)

        vm.onLogLevelChange(LogLevel.DEBUG)

        assertEquals(LogLevel.DEBUG, vm.form.value.logLevel)
    }

    @Test
    fun multipleFieldChangesAccumulate() {
        val vm = SettingsViewModel(sync = fakeSync)

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
                backendPort = 8888,
                webappPort = 9999,
                logLevel = LogLevel.WARNING,
            ),
            vm.form.value,
        )
    }

    @Test
    fun saveRecordsCurrentFormAsLastSaved() {
        val vm = SettingsViewModel(sync = fakeSync)
        vm.onProviderChange("anthropic")
        vm.onModelChange("claude-sonnet-4-5")
        vm.onApiKeyChange("sk-test")
        vm.onBackendPortChange(8888)

        vm.save()

        assertEquals(vm.form.value, vm.lastSaved.value)
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
        val vm = SettingsViewModel(repo = repo, sync = fakeSync)

        assertEquals(persisted, vm.form.value)
        assertEquals(persisted, vm.lastSaved.value)
    }

    @Test
    fun initLeavesLastSavedNullWhenRepoReturnsNull() {
        val repo = RecordingSettingsRepo(nextLoad = null)
        val vm = SettingsViewModel(repo = repo, sync = fakeSync)

        assertEquals(SettingsForm.DEFAULTS, vm.form.value)
        assertNull(vm.lastSaved.value)
    }

    @Test
    fun saveCallsRepoSaveWithCurrentForm() {
        val repo = RecordingSettingsRepo(nextLoad = null)
        val vm = SettingsViewModel(repo = repo, sync = fakeSync)
        vm.onProviderChange("anthropic")
        vm.onApiKeyChange("sk-new")

        vm.save()

        assertEquals(vm.form.value, repo.lastSaved)
    }

    @Test
    fun saveUpdatesLastSavedButLeavesFormUnchanged() {
        val repo = RecordingSettingsRepo(nextLoad = null)
        val vm = SettingsViewModel(repo = repo, sync = fakeSync)
        vm.onModelChange("claude-sonnet-4-5")

        val formBeforeSave = vm.form.value
        assertNull(vm.lastSaved.value) // not saved yet

        vm.save()

        assertEquals(formBeforeSave, vm.form.value) // unchanged
        assertEquals(formBeforeSave, vm.lastSaved.value) // now saved
    }

    @Test
    fun inMemoryRepoBehavesLikePhase56() {
        val vm = SettingsViewModel(sync = fakeSync) // default = InMemory

        assertEquals(SettingsForm.DEFAULTS, vm.form.value)
        assertNull(vm.lastSaved.value)

        vm.onModelChange("gpt-4o")
        vm.save()

        assertEquals(vm.form.value, vm.lastSaved.value)
    }

    // --- Phase 6.5 (backend sync) ---------------------------------

    @Test
    fun saveCallsConfigSyncWithCurrentForm() {
        val vm = SettingsViewModel(sync = fakeSync)
        vm.onProviderChange("anthropic")
        vm.onModelChange("claude-sonnet-4-5")
        vm.onApiKeyChange("sk-test")
        vm.onBackendPortChange(8888)
        vm.onWebappPortChange(9999)
        vm.onLogLevelChange(LogLevel.WARNING)

        vm.save()

        // The fake captured the sync call. We
        // assert on the form fields the wire
        // contract cares about; the logLevel
        // field is intentionally NOT sent
        // (the backend has no concept of log
        // level — see ConfigSync kdoc).
        assertEquals(1, fakeSync.requests.size)
        val req = fakeSync.requests[0]
        assertEquals("anthropic", req.provider)
        assertEquals("claude-sonnet-4-5", req.model)
        assertEquals("sk-test", req.apiKey)
        assertEquals(8888, req.ports.backend)
        assertEquals(9999, req.ports.flask)
    }

    @Test
    fun saveUpdatesLastSavedEvenIfSyncFails() {
        // Best-effort sync: the local save is
        // the authoritative state, so a sync
        // failure must not roll back the
        // status pill or leave the form in a
        // half-saved state. The "Modified" →
        // "Saved" flip should still happen.
        val failingSync = FakeConfigSync(nextResult = false)
        val vm = SettingsViewModel(sync = failingSync)
        vm.onModelChange("gpt-4o")

        vm.save()

        assertEquals(vm.form.value, vm.lastSaved.value)
        // The sync was attempted exactly once.
        assertEquals(1, failingSync.requests.size)
    }

    @Test
    fun saveCallsConfigSyncExactlyOnce() {
        val vm = SettingsViewModel(sync = fakeSync)
        vm.save()
        vm.save()
        vm.save()

        assertEquals(3, fakeSync.requests.size)
    }
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

/**
 * Test fake for [ConfigSync]. Captures every
 * outbound `sync(form)` call into [requests] (a
 * list so the test sees the order) and returns
 * [nextResult] (default = `true` for happy path).
 *
 * We fake at the [ConfigSync] level (not at the
 * [com.seed.app.data.BackendApi] level) because
 * the SettingsViewModel test cares about
 * the `ConfigSync` boundary: the local-save
 * path is tested via [RecordingSettingsRepo]
 * separately, and the wire-format mapping
 * (`SettingsForm` → `ConfigRequest`) is tested
 * in [ConfigSyncTest]. No need to wire a
 * `BackendApi` mock + real `ConfigSync` for
 * the ViewModel behaviour tests.
 */
internal class FakeConfigSync(
    private var nextResult: Boolean = true,
) : ConfigSync(backend = NoopBackendApi) {
    val requests: MutableList<ConfigRequest> = mutableListOf()

    override suspend fun sync(form: SettingsForm): Boolean {
        requests.add(toRequest(form))
        return nextResult
    }
}

/**
 * Minimal [com.seed.app.data.BackendApi] stub the
 * [FakeConfigSync] can wrap. We only need the
 * type to be valid (the [FakeConfigSync] overrides
 * [ConfigSync.sync] and never calls the real
 * `putConfig`). The methods throw to surface
 * accidental use in a test.
 */
private object NoopBackendApi : com.seed.app.data.BackendApi {
    override suspend fun health() =
        TODO("FakeConfigSync never calls health()")

    override suspend fun shellExec(request: com.seed.app.data.ShellExecRequest) =
        TODO("FakeConfigSync never calls shellExec()")

    override suspend fun putConfig(payload: ConfigRequest) =
        TODO("FakeConfigSync never calls putConfig()")
}
