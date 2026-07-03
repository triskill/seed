package com.seed.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.seed.app.ui.settings.LogLevel
import com.seed.app.ui.settings.SettingsForm
import kotlinx.coroutines.flow.first

// Top-level DataStore name. The `preferencesDataStore`
// delegate requires a `const val` String at
// top-level scope (it can't reference a companion
// constant or a runtime expression), so the name
// lives here next to the delegate itself.
private const val DATASTORE_NAME = "settings"

private const val SECURE_PREFS_NAME = "secure_settings"

private val KEY_PROVIDER = stringPreferencesKey("provider")
private val KEY_MODEL = stringPreferencesKey("model")
private val KEY_BACKEND_PORT = intPreferencesKey("backend_port")
private val KEY_WEBAPP_PORT = intPreferencesKey("webapp_port")
private val KEY_LOG_LEVEL = intPreferencesKey("log_level")

// SharedPreferences key for the API key. (Not a
// DataStore key because we need encryption at rest
// for this field.)
private const val KEY_API_KEY = "api_key"

/**
 * Top-level extension that gives the app a single
 * `DataStore<Preferences>` instance keyed on the
 * Context. The `preferencesDataStore` delegate is
 * the recommended DataStore-Preferences idiom; it
 * ensures one DataStore per process per name and
 * handles the file path (`context.filesDir /
 * datastore / settings.preferences_pb`) for us.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATASTORE_NAME,
)

/**
 * Production [SettingsRepo] backed by
 * `DataStore-Preferences` + `EncryptedSharedPreferences`.
 *
 * **Why two stores?** The Settings form has two
 * kinds of fields:
 *
 *   - **Non-secret** (provider, model, backend
 *     port, webapp port, log level) — stored in
 *     `DataStore-Preferences`, which is a
 *     coroutine-friendly, typed key-value store
 *     designed for preferences. The store is
 *     plain-text on disk (DataStore doesn't
 *     encrypt its file).
 *   - **Secret** (apiKey) — stored in
 *     `EncryptedSharedPreferences`, which
 *     AES-encrypts the prefs file at rest using a
 *     master key in the Android Keystore. The
 *     secret never touches the plain-text
 *     DataStore file.
 *
 * **Why not just DataStore for everything?** The
 * master-key scheme in `EncryptedSharedPreferences`
 * is the only built-in API for at-rest encryption
 * of preference values; DataStore doesn't have a
 * first-class encryption story. Keeping the secret
 * in a separate encrypted store is the cleanest
 * way to satisfy "API key at rest is encrypted"
 * without writing our own DataStore serializer.
 *
 * **What "saved" means:** [load] returns `null`
 * on a fresh install (no `provider` key in
 * DataStore). The ViewModel uses `null` to
 * distinguish "never been saved" from "saved
 * with default values" — the UI's "Modified" /
 * "Saved" status pill depends on this.
 *
 * **Why this is not unit-testable on the JVM:**
 * the constructor takes a `Context` (for
 * `DataStore` and `EncryptedSharedPreferences`),
 * and the encryption layer is in the Android
 * Keystore. The contract tests for [SettingsRepo]
 * live in `SettingsRepoTest` (using
 * [SettingsRepo.InMemory] + a `RecordingSettingsRepo`
 * in `SettingsViewModelTest`); this class is
 * exercised by the Phase 6+ instrumented tests.
 */
class AndroidSettingsRepo(context: Context) : SettingsRepo {

    private val ds: DataStore<Preferences> = context.settingsDataStore

    private val securePrefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun load(): SettingsForm? {
        val prefs = ds.data.first()

        // "Saved" is signalled by the presence of
        // the `provider` key. A fresh install has
        // no keys, so we return null. A partially-
        // written form (e.g. from a crash mid-save)
        // is treated as not-saved — the user just
        // has to tap Save again. The cost is low and
        // it keeps the contract simple.
        val provider = prefs[KEY_PROVIDER] ?: return null
        val model = prefs[KEY_MODEL] ?: return null
        val backendPort = prefs[KEY_BACKEND_PORT] ?: return null
        val webappPort = prefs[KEY_WEBAPP_PORT] ?: return null
        val logLevelOrdinal = prefs[KEY_LOG_LEVEL] ?: return null
        val logLevel = LogLevel.values().getOrNull(logLevelOrdinal) ?: return null

        val apiKey = securePrefs.getString(KEY_API_KEY, null).orEmpty()

        return SettingsForm(
            provider = provider,
            model = model,
            apiKey = apiKey,
            backendPort = backendPort,
            webappPort = webappPort,
            logLevel = logLevel,
        )
    }

    override suspend fun save(form: SettingsForm) {
        // DataStore is the source of truth for the
        // non-secret fields; EncryptedSharedPreferences
        // for the secret. The two writes are not
        // transactional across stores, but DataStore
        // is atomic per-edit and SharedPreferences'
        // `apply()` is also atomic (it writes to
        // memory immediately, syncs to disk
        // asynchronously). A crash mid-save would
        // leave the stores in a consistent-enough
        // state for the next `load()` to either
        // succeed (if the DataStore edit landed)
        // or return null (if it didn't) — no
        // half-form is observable.
        ds.edit { prefs ->
            prefs[KEY_PROVIDER] = form.provider
            prefs[KEY_MODEL] = form.model
            prefs[KEY_BACKEND_PORT] = form.backendPort
            prefs[KEY_WEBAPP_PORT] = form.webappPort
            prefs[KEY_LOG_LEVEL] = form.logLevel.ordinal
        }
        securePrefs.edit().putString(KEY_API_KEY, form.apiKey).apply()
    }
}
