package com.seed.app.data

/**
 * One event the orchestrator broadcasts to chat
 * clients over the WebSocket.
 *
 * **Phase 6.2** introduces this sealed class as
 * the typed counterpart to the backend's WS
 * `type` strings (defined in
 * `seed_backend.events.py`: `middleman_line`,
 * `worker_line`, `complete`, `app_reload`,
 * `error`). The wire format is a flat JSON object
 * with a `type` discriminator; the Kotlin side
 * parses each one into a [ChatEvent] variant.
 *
 * **Why a sealed class:** the `when` in the
 * ChatViewModel (Phase 6.3) is exhaustively
 * checked at compile time, so a new event kind
 * in the backend (e.g. `tool_start`) is a
 * compile error here, not a silent
 * never-rendered code path.
 *
 * **Event semantics:**
 *   - [MiddlemanLine] / [WorkerLine] — a chunk
 *     of the corresponding agent's text output.
 *     The orchestrator splits the agent's
 *     `text_delta` events into line-sized chunks;
 *     the chat UI treats each as a separate
 *     row in the message stream. Tool events
 *     (e.g. `tool_execution_start`) come through
 *     as a [WorkerLine] whose `line` field is a
 *     JSON object — the ChatViewModel can parse
 *     and render it as a tool card.
 *   - [Complete] — the worker emitted
 *     `<task:done .../>`. The optional [summary]
 *     is the worker's one-paragraph report.
 *   - [AppReload] — broadcast right after
 *     [Complete]; the chat UI is expected to
 *     trigger an App-screen refresh (Phase 10
 *     will wire the actual `webView.reload()`).
 *   - [Error] — something went wrong (the
 *     agent crashed, the WebSocket dropped, the
 *     dispatch JSON was malformed). [message]
 *     is human-readable.
 */
sealed class ChatEvent {

    /** A chunk of the middle-man's output. */
    data class MiddlemanLine(val line: String) : ChatEvent()

    /**
     * A chunk of the worker's output, or a tool
     * event encoded as a JSON string. The
     * ChatViewModel in Phase 6.3 will inspect
     * the first character (`{` = JSON) to decide
     * between text vs. tool rendering.
     */
    data class WorkerLine(val line: String) : ChatEvent()

    /**
     * The worker emitted `<task:done summary="..."/>`.
     * [summary] is the worker's self-report; may
     * be `null` for the bare `<task:done/>` form
     * (v0.1 placeholder).
     */
    data class Complete(val summary: String?) : ChatEvent()

    /**
     * Worker finished; the App screen should
     * reload. Broadcast right after [Complete].
     */
    data object AppReload : ChatEvent()

    /**
     * An error occurred. [message] is the
     * orchestrator's best-effort description.
     */
    data class Error(val message: String) : ChatEvent()
}
