package com.seed.app.data

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * HTTP client for the FastAPI backend (`/health`,
 * `/shell/exec`).
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
