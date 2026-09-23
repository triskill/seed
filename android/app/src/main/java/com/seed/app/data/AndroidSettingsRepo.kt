package com.seed.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.seed.app.ui.settings.LogLevel
import com.seed.app.ui.settings.SettingsForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

// Top-level DataStore name. The `preferencesDataStore`
// delegate requires a `const val` String at
// top-level scope (it can't reference a companion
// constant or a runtime expression), so the name
// lives here next to the delegate itself.
private const val DATASTORE_NAME = "settings"

private const val SECURE_PREFS_NAME = "secure_settings"

private val KEY_PROVIDER = stringPreferencesKey("provider")
private val KEY_MODEL = stringPreferencesKey("model")
private val KEY_HOST = stringPreferencesKey("host")
private val KEY_BACKEND_PORT = intPreferencesKey("backend_port")
private val KEY_WEBAPP_PORT = intPreferencesKey("webapp_port")
private val KEY_LOG_LEVEL = intPreferencesKey("log_level")
private val KEY_THINKING_LEVEL = stringPreferencesKey("thinking_level")

// Key in the obsolete encrypted store, read only for migration.
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

/** Best-effort migration reader; never creates the encrypted store on new installs. */
internal class LegacyCredential(
    private val exists: () -> Boolean,
    private val open: () -> SharedPreferences,
) {
    fun read(): String {
        if (!exists()) return ""
        return try {
            open().getString(KEY_API_KEY, null).orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        if (!exists()) return
        check(open().edit().remove(KEY_API_KEY).commit()) {
            "Could not clear legacy Android credential"
        }
    }
}

/**
 * Production [SettingsRepo] backed by
 * `DataStore-Preferences` for app preferences. Credentials belong to the
 * backend; an existing encrypted Android credential is read only for a
 * migration warning and retained until a successful backend import.
 *
 * **What "saved" means:** [load] returns `null`
 * on a fresh install (no `provider` key in
 * DataStore). The ViewModel uses `null` to
 * distinguish "never been saved" from "saved
 * with default values" — the UI's "Modified" /
 * "Saved" status pill depends on this.
 *
 */
class AndroidSettingsRepo(context: Context) : SettingsRepo {

    private val ds: DataStore<Preferences> = context.settingsDataStore

    private val legacyCredential = LegacyCredential(
        exists = { File(context.applicationInfo.dataDir, "shared_prefs/$SECURE_PREFS_NAME.xml").exists() },
        open = {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, SECURE_PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        },
    )

    override suspend fun load(): SettingsForm? {
        val prefs = ds.data.first()

        // "Saved" is signalled by the presence of
        // the `provider` key. A fresh install has
        // no keys, so we return null. A partially-
        // written form (e.g. from a crash mid-save)
        // is treated as not-saved — the user just
        // has to tap Save again. The cost is low and
        // it keeps the contract simple.
        val apiKey = withContext(Dispatchers.IO) { legacyCredential.read() }
        return prefs.toSettingsForm(apiKey)
    }

    override suspend fun clearLegacyCredential() {
        withContext(Dispatchers.IO) {
            legacyCredential.clear()
        }
    }

    override suspend fun save(form: SettingsForm) {
        // Only app preferences are saved here. Never remove a legacy key
        // until the backend has accepted a replacement credential.
        ds.edit { prefs -> prefs.putNonSecretSettings(form) }

    }
}

/** Decode app preferences and expose a legacy key only for migration. */
internal fun Preferences.toSettingsForm(apiKey: String): SettingsForm? {
    val provider = this[KEY_PROVIDER] ?: return null
    val model = this[KEY_MODEL] ?: return null
    val backendPort = this[KEY_BACKEND_PORT] ?: return null
    val webappPort = this[KEY_WEBAPP_PORT] ?: return null
    val logLevelOrdinal = this[KEY_LOG_LEVEL] ?: return null
    val logLevel = LogLevel.values().getOrNull(logLevelOrdinal) ?: return null
    val thinkingLevel = this[KEY_THINKING_LEVEL] ?: SettingsForm.DEFAULTS.thinkingLevel

    return SettingsForm(
        provider = provider,
        model = model,
        apiKey = apiKey,
        thinkingLevel = thinkingLevel,
        // Settings saved before Phase 9 have no host. Migrate those installs
        // to the embedded-runtime default instead of discarding the form.
        host = this[KEY_HOST] ?: SettingsForm.DEFAULTS.host,
        backendPort = backendPort,
        webappPort = webappPort,
        logLevel = logLevel,
    )
}

/** Persist every non-secret field in one atomic DataStore edit. */
internal fun MutablePreferences.putNonSecretSettings(form: SettingsForm) {
    this[KEY_PROVIDER] = form.provider
    this[KEY_MODEL] = form.model
    this[KEY_HOST] = form.host
    this[KEY_BACKEND_PORT] = form.backendPort
    this[KEY_WEBAPP_PORT] = form.webappPort
    this[KEY_LOG_LEVEL] = form.logLevel.ordinal
    this[KEY_THINKING_LEVEL] = form.thinkingLevel
}
