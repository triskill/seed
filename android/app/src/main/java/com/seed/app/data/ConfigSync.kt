package com.seed.app.data

import com.seed.app.ui.settings.SettingsForm

/**
 * Sends the Android-side [SettingsForm] to the
 * backend's `PUT /config` endpoint.
 *
 * **Phase 6.5** introduces this class as the
 * bridge between the Settings UI (which owns the
 * authoritative form, in DataStore + EncryptedSharedPreferences)
 * and the backend's `config.json` (which the
 * orchestrator will read on its next start in
 * Phase 7+).
 *
 * **Why a separate class (not a method on
 * [SettingsViewModel]):** the ViewModel already
 * has the [SettingsRepo] for local persistence.
 * Mixing in the network call would couple the
 * two persistence mechanisms and make the
 * ViewModel harder to test (the existing tests
 * use `SettingsRepo.InMemory`; the network
 * behaviour is best tested against a
 * [com.seed.app.data.BackendApi] fake). A
 * dedicated [ConfigSync] class is the natural
 * seam: constructor takes a [BackendApi],
 * single [sync] method, no state.
 *
 * **One-way write, no read back:** the Android
 * form is the source of truth for the user's
 * settings (the local `SettingsRepo.load()`
 * hydrates the form on init — Phase 5.7). The
 * backend's `config.json` is a sink. There's no
 * `GET /config` route (the Android side never
 * reads the backend's view of the config). A
 * future task may add one for diagnostics
 * (e.g. a "View backend config" button in
 * Settings) but it's out of scope for v0.1.
 *
 * **The mapping** from [SettingsForm] to
 * [ConfigRequest]:
 *   - [SettingsForm.provider] → [ConfigRequest.provider]
 *   - [SettingsForm.model] → [ConfigRequest.model]
 *   - [SettingsForm.apiKey] → [ConfigRequest.apiKey]
 *     (sent over the loopback HTTP connection;
 *     the field name is `api_key` per Moshi's
 *     `@Json(name = ...)` mapping on the DTO)
 *   - [SettingsForm.backendPort] → [ConfigRequest.ports.backend]
 *   - [SettingsForm.webappPort] → [ConfigRequest.ports.flask]
 *   - [SettingsForm.logLevel] is **not** sent.
 *     The orchestrator has no concept of log
 *     level yet (Phase 7+ will add a
 *     `RuntimeService` log view). The Android
 *     side keeps `logLevel` as a client-side
 *     concern; the backend's `config.json` has
 *     no place to store it.
 */
open class ConfigSync(
    private val backend: BackendApi,
) {
    /**
     * PUT the [form] to `PUT /config`. Returns
     * `true` if the backend accepted the write,
     * `false` on any failure (network down,
     * HTTP 4xx/5xx, `ok=false` response).
     *
     * **Phase 6.5** callers (the
     * `SettingsViewModel.save()`) treat
     * `false` as a soft error: the local
     * DataStore save still succeeded, so the
     * user's settings round-trip on the next
     * app start. The "sync failed" outcome is
     * logged but not surfaced as a UI banner
     * (a future task may add one). The
     * reasoning: the local save is the
     * authoritative state; the backend sync
     * is best-effort and the user can retry
     * by tapping Save again.
     */
    open suspend fun sync(form: SettingsForm): Boolean {
        val request = toRequest(form)
        return try {
            val response = backend.putConfig(request)
            response.ok
        } catch (e: Exception) {
            // Network down, HTTP 4xx/5xx, Moshi
            // deserialization failure, etc. We
            // swallow and report `false` so the
            // caller can decide what to do (v0.1:
            // log + move on).
            false
        }
    }

    /**
     * Pure mapping from the UI-level
     * [SettingsForm] to the wire-level
     * [ConfigRequest]. Visible for testing so
     * the test can verify the field names +
     * the log-level drop without going through
     * the full `sync()` round-trip.
     */
    internal fun toRequest(form: SettingsForm): ConfigRequest = ConfigRequest(
        provider = form.provider,
        model = form.model,
        apiKey = form.apiKey,
        ports = ConfigPorts(
            backend = form.backendPort,
            flask = form.webappPort,
        ),
    )
}
