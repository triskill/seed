package com.seed.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel].
 *
 * Phase 5.6 ships a local-only ViewModel — no
 * DataStore, no `SettingsRepo`. The form lives in
 * a `StateFlow<SettingsForm>` and `save()` records
 * the current form into a `lastSaved: StateFlow`
 * so the screen can show "Saved" / "Modified"
 * feedback. Phase 5.7 will add a `SettingsRepo`
 * (DataStore + EncryptedSharedPreferences for the
 * API key) and rewrite `save()` to persist. The
 * public API
 * ([SettingsViewModel.form],
 * [SettingsViewModel.lastSaved], and the six
 * onXChange setters) is the same shape Phase 5.7
 * will need, so this test file is stable across
 * both phases.
 */
class SettingsViewModelTest {

    @Test
    fun initialStateHasDefaultsAndNullLastSaved() {
        val vm = SettingsViewModel()

        assertEquals(SettingsForm.DEFAULTS, vm.form.value)
        assertNull(vm.lastSaved.value)
    }

    @Test
    fun onProviderChangeUpdatesProviderAndLeavesOtherFieldsUnchanged() {
        val vm = SettingsViewModel()
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
}
