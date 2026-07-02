package com.seed.app.ui.settings

/**
 * The structured form values for the Settings tab.
 *
 * Modelled as an immutable `data class` so the
 * ViewModel can use `copy(...)` when a single field
 * changes (one new instance, the rest of the form
 * preserved). The default values
 * ([SettingsForm.DEFAULTS]) match the dev defaults
 * baked into the build config so a fresh install
 * looks consistent across app / webapp / shell
 * tabs.
 *
 * **Field semantics:**
 *   - [provider] — model provider name. The full
 *     list of supported providers lives in
 *     [SettingsForm.KNOWN_PROVIDERS] for the
 *     dropdown. Free-form for now; Phase 6 will
 *     switch to a closed enum.
 *   - [model] — model name (e.g. "gpt-4o",
 *     "claude-sonnet-4-5"). Free-form; the
 *     provider's API is the source of truth for
 *     what models it accepts.
 *   - [apiKey] — API key for the provider. Phase
 *     5.7 will store this in
 *     `EncryptedSharedPreferences` rather than
 *     the regular DataStore so it lives in the
 *     Android keystore.
 *   - [backendPort] / [webappPort] — ports for the
 *     two dev backends. Defaults match the
 *     `BACKEND_DEV_URL` and `WEBAPP_DEV_URL`
 *     build-config values.
 *   - [logLevel] — minimum level for the in-app
 *     log view (Phase 7+ will surface these
 *     somewhere).
 */
data class SettingsForm(
    val provider: String = "openai",
    val model: String = "gpt-4o",
    val apiKey: String = "",
    val backendPort: Int = 7777,
    val webappPort: Int = 7778,
    val logLevel: LogLevel = LogLevel.INFO,
) {
    companion object {
        /** Hardcoded defaults for a fresh install. */
        val DEFAULTS: SettingsForm = SettingsForm()

        /**
         * Providers the dropdown offers. Free-form
         * (a `String`), not a closed enum, because
         * new providers land over time and the
         * list is advisory — typing a value not
         * in the list is allowed.
         */
        val KNOWN_PROVIDERS: List<String> = listOf(
            "openai",
            "anthropic",
            "local",
        )
    }
}

/**
 * Log severity levels, in increasing order. The
 * [displayName] is what the user sees in the
 * dropdown; the enum constant name is what the
 * in-app logger keys off.
 *
 * Order matters: the in-app logger will use
 * `LogLevel.ordinal` to compare "is this level
 * enabled?" against the user's setting.
 */
enum class LogLevel(val displayName: String) {
    DEBUG("Debug"),
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error"),
}
