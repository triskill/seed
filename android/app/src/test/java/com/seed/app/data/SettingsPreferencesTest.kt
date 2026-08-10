package com.seed.app.data

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.seed.app.ui.settings.LogLevel
import com.seed.app.ui.settings.SettingsForm
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPreferencesTest {

    @Test
    fun freshInstallDefaultsToEmbeddedLoopbackHost() {
        assertEquals("127.0.0.1", SettingsForm.DEFAULTS.host)
    }

    @Test
    fun nonSecretSettingsRoundTripIncludesHost() {
        val form = SettingsForm(
            provider = "local",
            model = "test-model",
            apiKey = "not-written-here",
            host = "10.0.2.2",
            backendPort = 9001,
            webappPort = 9002,
            logLevel = LogLevel.DEBUG,
        )
        val preferences = mutablePreferencesOf()

        preferences.putNonSecretSettings(form)

        assertEquals(
            form.copy(apiKey = "restored-secret"),
            preferences.toSettingsForm(apiKey = "restored-secret"),
        )
    }

    @Test
    fun legacySavedSettingsWithoutHostMigrateToLoopbackDefault() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("provider") to "openai",
            stringPreferencesKey("model") to "gpt-4o",
            intPreferencesKey("backend_port") to 7777,
            intPreferencesKey("webapp_port") to 7778,
            intPreferencesKey("log_level") to LogLevel.INFO.ordinal,
        )

        val restored = preferences.toSettingsForm(apiKey = "secret")

        assertEquals("127.0.0.1", restored?.host)
        assertEquals("secret", restored?.apiKey)
    }
}
