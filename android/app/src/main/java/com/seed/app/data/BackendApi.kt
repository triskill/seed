package com.seed.app.data

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * HTTP client for the FastAPI backend (`/health`,
 * `/shell/exec`, `/config`).
 *
 * **Phase 6.1** introduces this Retrofit interface
 * as the single typed entry point for every HTTP
 * call the Android app makes. The `suspend` modifier
 * on each method means callers (the Shell and
 * Settings ViewModels in Tasks 6.4 and 6.5) launch
 * the call in their own `viewModelScope` and get
 * structured concurrency for free — no `Call<...>`
 * wrapping, no manual `enqueue`.
 *
 * **JSON shape** — every DTO in this file matches
 * the backend's Pydantic models field-for-field.
 * Snake-case JSON fields (such as `exit_code`)
 * are mapped to camelCase Kotlin properties via
 * Moshi's `@Json(name = "...")` annotation so the
 * Kotlin code reads naturally. The DTOs are
 * deliberately separate from any UI-level data
 * class (e.g. [com.seed.app.ui.settings.SettingsForm])
 * so a future backend field rename doesn't ripple
 * through the UI.
 *
 * **What this interface does NOT model:**
 *   - The `/chat` WebSocket — that's a different
 *     transport (full-duplex) and lives in
 *     `ChatWebSocket.kt` (Phase 6.2).
 *   - Long-polling or streaming responses. Every
 *     endpoint here returns a single response body.
 *     The shell's PTY output is delivered all-at-once
 *     (not streamed), and the chat stream comes
 *     through the WebSocket.
 */
interface BackendApi {

    /**
     * `GET /health` — liveness + Flask readiness probe.
     *
     * Returns a small JSON object the Phase 8
     * foreground service uses to decide whether the
     * orchestrator is ready for chat traffic. Also
     * useful for the Settings "test connection"
     * button in a future task.
     *
     * The backend's Pydantic `BaseModel` is implicit
     * (a `dict` literal in [seed_backend.service.health]),
     * so we model the response as a flat data class.
     */
    @GET("health")
    suspend fun health(): HealthResponse

    /**
     * `POST /shell/exec` — run a command, return its
     * captured output.
     *
     * The backend runs the command inside a PTY (Task
     * 1.2), so output is a single merged stream with
     * ANSI codes stripped server-side (the Shell
     * screen displays plain text — Phase 5.5's
     * `OutputLineRow` is monospaced but does not
     * parse ANSI). The response is all-at-once, not
     * streamed: the Phase 1.3 truncation caps (5000
     * lines / 1 MiB) apply, and [ShellExecResponse.truncated]
     * flags a hit on either.
     *
     * Throws `retrofit2.HttpException` on a non-2xx
     * status (the backend returns 200 only; 422 on
     * a missing `command` field is the only other
     * documented code, and the Shell screen guards
     * against empty input before calling).
     */
    @POST("shell/exec")
    suspend fun shellExec(@Body request: ShellExecRequest): ShellExecResponse

    /**
     * `PUT /config` — write the Android-side settings
     * (provider, model, ports) to the
     * backend's `config.json` so the next orchestrator
     * restart picks them up.
     *
     * Phase 6.5 adds this endpoint to the backend
     * (the route is a thin wrapper around
     * [seed_backend.config.Config.save]). The
     * Android side calls it from
     * [com.seed.app.data.ConfigSync] after a
     * successful [com.seed.app.data.SettingsRepo.save].
     *
     * The response is a 200 OK with a small ack body
     * (`{"ok": true}`) so the client can tell a
     * successful write apart from a 200-with-error-
     * payload if the backend ever needs that.
     */
    @PUT("config")
    suspend fun putConfig(@Body payload: ConfigRequest): ConfigResponse
}

/**
 * `GET /health` response.
 *
 * The `flask` field is `"up"` / `"down"` — the
 * string is the backend's contract (a free-form
 * status string, not an enum), and we model it
 * as a `String` so a future "starting" / "error"
 * state doesn't need a code change here.
 */
data class HealthResponse(
    val status: String,
    val flask: String,
)

/**
 * `POST /shell/exec` request body.
 *
 * `min_length=1` is enforced server-side (Pydantic
 * `Field(..., min_length=1)`); the Shell ViewModel
 * trims the input and no-ops on empty before calling,
 * so 422 is never expected in practice.
 */
data class ShellExecRequest(
    val command: String,
)

/**
 * `POST /shell/exec` response body.
 *
 * Mirrors [seed_backend.service.ShellExecResponse].
 * [exitCode] is the child's exit status (0 on
 * success, non-zero on error). [truncated] is
 * `true` if the server-side 5000-line / 1-MiB cap
 * kicked in — the Shell screen renders a warning
 * row before the exit status when this is true.
 */
data class ShellExecResponse(
    val stdout: String,
    val stderr: String,
    @Json(name = "exit_code") val exitCode: Int,
    val truncated: Boolean = false,
)

/**
 * `PUT /config` request body.
 *
 * Mirrors the backend's
 * [seed_backend.config.Config] dataclass. The
 * backend's `load()` method tolerates missing
 * fields (falls back to dataclass defaults), so a
 * partial write is recoverable — but the Android
 * side always sends all four fields populated.
 *
 * The API key is deliberately absent. Android keeps it in
 * EncryptedSharedPreferences and injects it directly into the embedded
 * process environment at startup. Sending it through this unauthenticated
 * loopback endpoint would expose it to debug HTTP logging and duplicate it in
 * the guest's plaintext `config.json`.
 */
data class ConfigRequest(
    val provider: String,
    val model: String,
    val ports: ConfigPorts,
)

/**
 * The `ports` sub-object of [ConfigRequest].
 *
 * Two keys: `backend` (FastAPI, default 7777) and
 * `flask` (webapp, default 7778). Matches the
 * backend's [seed_backend.config.DEFAULT_PORTS]
 * dict field-for-field.
 */
data class ConfigPorts(
    val backend: Int,
    val flask: Int,
)

/**
 * `PUT /config` response body.
 *
 * A small ack: `{"ok": true}` on success. The
 * ConfigSync caller checks [ok] and surfaces a
 * "sync failed" error in the Settings UI if it's
 * `false` (a future task may add a banner; for
 * Phase 6.5 we just log it).
 */
data class ConfigResponse(
    val ok: Boolean,
)
