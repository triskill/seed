package com.seed.app.ui.settings

import com.seed.app.data.ProviderCatalog

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
 *   - [provider] — provider ID from the curated
 *     [ProviderCatalog].
 *   - [model] — exact model ID returned by Pi's
 *     authenticated catalog.
 *   - [apiKey] — provider credential stored in
 *     Android Keystore-backed encrypted preferences.
 *   - [host] — Android-side host for backend connections. It defaults
 *     to device loopback for the embedded runtime. Phase 10 will expose
 *     host switching in the UI and rebuild active clients when it changes.
 *   - [backendPort] / [webappPort] — ports for the
 *     two backends. Defaults match the embedded runtime's fixed ports.
 *   - [logLevel] — minimum level for the in-app
 *     log view (Phase 7+ will surface these
 *     somewhere).
 */
data class SettingsForm(
    val provider: String = "opencode-go",
    val model: String = "deepseek-v4-flash",
    val apiKey: String = "",
    val thinkingLevel: String = "low",
    val host: String = "127.0.0.1",
    val backendPort: Int = 7777,
    val webappPort: Int = 7778,
    val logLevel: LogLevel = LogLevel.INFO,
) {
    companion object {
        /** Hardcoded defaults for a fresh install. */
        val DEFAULTS: SettingsForm = SettingsForm()

        /** Compatibility provider IDs retained for older callers. */
        /** Compatibility view for older callers; new UI uses [ProviderCatalog]. */
        val KNOWN_PROVIDERS: List<String> = ProviderCatalog.PROVIDERS.map { it.id }
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
