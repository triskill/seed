package com.seed.app.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * WebSocket client for the orchestrator's
 * `ws://.../chat` endpoint.
 *
 * **Phase 6.2** introduces this class as the
 * transport layer the ChatViewModel (Phase 6.3)
 * will collect from. The wire format is JSON
 * frames; the orchestrator's `handle_chat` route
 * (in `seed_backend/chat.py`) accepts
 * `{"type": "user_message", "text": "..."}` on
 * the inbound side and broadcasts events
 * ([ChatEvent] sealed class) on the outbound
 * side.
 *
 * **Lifecycle:**
 *   - [connect] starts a long-running coroutine
 *     that opens the WebSocket and re-opens it
 *     on any failure (network error, server
 *     close, mid-flight drop). The same coroutine
 *     tears down on [disconnect] or on a
 *     `scope.cancel()`.
 *   - [send] writes a `user_message` frame over
 *     the current WebSocket. Returns `false` (no
 *     exception) if not connected so the
 *     ViewModel can decide whether to buffer,
 *     show an error, or drop.
 *   - [disconnect] closes the WebSocket cleanly
 *     (close code 1000) and cancels the
 *     reconnect loop. After [disconnect], the
 *     instance is unusable — call [connect] to
 *     start a fresh connection.
 *
 * **Reconnect with backoff:** on any failure,
 * the connection loop sleeps for `1s, 2s, 4s,
 * 8s, 16s, 30s, 30s, ...` before retrying. The
 * schedule resets to `1s` on a successful
 * `onOpen`. This is intentionally simple
 * (exponential, no jitter) — the orchestrator
 * is local, the failure mode we're defending
 * against is "user rebooted the dev box", and
 * jitter would only delay recovery.
 *
 * **Why SharedFlow for [events], not Flow:**
 * the consumer (ChatViewModel) needs each event
 * exactly once. SharedFlow with no replay gives
 * us that without the cold-Flow restart-every-
 * collector dance. The buffer of 64 lets
 * the ViewModel fall a few frames behind the
 * producer without backpressure; if it falls
 * further behind, oldest events are dropped
 * (the chat UI prefers a fresh agent reply to
 * a complete archive of the agent's
 * chain-of-thought).
 *
 * **Why a per-instance [scope]:** the
 * WebSocket + reconnect loop should be tied
 * to the consumer's lifetime, not the
 * application process. The ChatViewModel
 * creates a ChatWebSocket in its `init`,
 * launches its collector in `viewModelScope`,
 * and calls [disconnect] in `onCleared`. The
 * internal scope is a `SupervisorJob` so a
 * single listener callback throwing doesn't
 * kill the whole loop.
 */
class ChatWebSocket(
    /**
     * HTTP base URL of the backend (e.g.
     * `http://10.0.2.2:7777/`). The constructor
     * converts this to the WebSocket URL by
     * swapping `http`→`ws` (and `https`→`wss`)
     * and appending `/chat`. The conversion is
     * naive but covers the v0.1 dev / LAN cases;
     * a future task may add a `wsUrl` config
     * field.
     */
    private val baseUrl: String,
    /**
     * OkHttp client used for the WebSocket
     * handshake. Defaults to a fresh client with
     * sensible timeouts; the test suite injects
     * a client with shorter timeouts so the
     * reconnect tests don't have to wait 30s.
     */
    private val client: OkHttpClient = defaultClient(),
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
) : ChatTransport {

    /**
     * Coarse-grained connection state for the UI
     * (e.g. a "Connecting..." pill in the chat
     * header in Phase 6.3). The transitions are:
     *
     *   DISCONNECTED → CONNECTING → CONNECTED
     *                                ↓
     *                          RECONNECTING → CONNECTING → CONNECTED
     *                                ↓
     *                          CONNECTED (on next failure)
     *
     * v0.1 never reaches FAILED — the reconnect
     * loop retries forever. A future task may add
     * a max-attempts cap.
     */
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var ws: WebSocket? = null

    private val backoff = ReconnectBackoff()
    private val userMessageAdapter = moshi.adapter(UserMessage::class.java)

    /**
     * Start the connection loop. Idempotent: a
     * second call while a loop is already
     * running is a no-op. The first [connect]
     * after a [disconnect] starts a fresh loop.
     */
    override fun connect() {
        if (loop?.isActive == true) return
        loop = scope.launch { runConnectionLoop() }
    }

    /**
     * Stop the connection loop and close the
     * WebSocket cleanly. Safe to call when
     * already disconnected (no-op). After
     * [disconnect] the instance can be
     * re-used by calling [connect] again.
     */
    fun disconnect() {
        loop?.cancel()
        loop = null
        ws?.close(NORMAL_CLOSURE_CODE, "client disconnect")
        ws = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Tear the instance down completely:
     * [disconnect] the WebSocket and cancel
     * the internal scope so the dispatcher
     * thread can wind down. After [close]
     * the instance is unusable — for tests
     * that need a clean shutdown (e.g.
     * MockWebServer's per-connection task
     * queue going idle). Production code
     * only needs [disconnect].
     */
    override fun close() {
        disconnect()
        scope.cancel()
    }

    /**
     * Send a `user_message` frame. Returns
     * `true` if the WebSocket accepted the
     * frame, `false` if the connection is not
     * up (so the caller can show a "not
     * connected" hint). Never throws — OkHttp's
     * `WebSocket.send(String)` returns `false`
     * on a closed socket; we propagate that.
     *
     * The text is JSON-escaped by Moshi (via
     * the [UserMessage] adapter) so quotes,
     * newlines, and backslashes in the input
     * are safe to send.
     */
    override fun send(text: String): Boolean {
        val socket = ws ?: return false
        return socket.send(userMessageAdapter.toJson(UserMessage(type = USER_MESSAGE_TYPE, text = text)))
    }

    private suspend fun runConnectionLoop() {
        while (currentCoroutineContext().isActive) {
            _state.value = ConnectionState.CONNECTING
            val closed = openOnce()
            // Suspends until the WebSocketListener
            // reports a close or failure. We don't
            // care about the cause here — every
            // non-explicit-disconnect is a reason
            // to back off and retry.
            closed.await()
            if (!currentCoroutineContext().isActive) break
            _state.value = ConnectionState.RECONNECTING
            delay(backoff.nextDelayMs())
        }
    }

    private fun openOnce(): CompletableDeferred<Unit> {
        val closed = CompletableDeferred<Unit>()
        val request = Request.Builder().url(wsUrl()).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoff.reset()
                _state.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = parseEvent(text) ?: return
                // `tryEmit` is synchronous and
                // thread-safe; the SharedFlow's
                // internal buffer lock serialises
                // concurrent calls, so the order
                // of `tryEmit` calls is the order
                // of `onMessage` calls. We avoid
                // the suspending `emit` (which
                // would need a `scope.launch`
                // wrapper) because OkHttp invokes
                // `onMessage` on its own
                // dispatcher thread and we don't
                // want the dispatcher to wait on
                // our internal scope. With
                // `BufferOverflow.DROP_OLDEST` and
                // a 64-slot buffer, `tryEmit`
                // always succeeds (it drops the
                // oldest event if the consumer
                // can't keep up).
                _events.tryEmit(event)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // The orchestrator only sends text
                // frames (JSON), not binary. Ignore.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Echo the close back to the server
                // (per RFC 6455) and let onClosed
                // resume the reconnect loop.
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Swallow the throwable — every
                // failure looks the same to the
                // reconnect loop (just back off
                // and try again). The events
                // SharedFlow would be the right
                // place to surface a "Connection
                // lost" ChatEvent, but v0.1 keeps
                // the state flow as the only
                // signal; the ChatViewModel
                // renders a "Reconnecting..."
                // pill in Phase 6.3.
                closed.complete(Unit)
            }
        })
        return closed
    }

    /**
     * Convert the HTTP base URL the constructor
     * took into the WebSocket URL. Strips a
     * trailing slash from the base and appends
     * `/chat`. `http`→`ws`, `https`→`wss`;
     * anything else is passed through
     * unchanged (so a future `ws://...` direct
     * config still works).
     */
    private fun wsUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> trimmed
        }
        return "$wsBase/chat"
    }

    /**
     * Parse one server→client WS text frame into
     * a [ChatEvent].
     *
     * The wire format is a flat JSON object with
     * a `type` discriminator and a small set of
     * well-known shapes per type. We use a
     * `Map<String, Any?>` adapter (rather than a
     * sealed Moshi DTO) because:
     *   - the shape is genuinely heterogeneous
     *     (different fields per `type`);
     *   - unknown types should be ignored
     *     silently (forward-compat);
     *   - the per-event fields are flat enough
     *     that a hand-written `when` is shorter
     *     than a sealed Moshi hierarchy.
     *
     * Returns `null` for malformed JSON, a
     * missing `type`, or an unknown `type` —
     * the [onMessage] handler drops the frame
     * in all those cases. Exceptions from Moshi
     * are caught and treated as malformed.
     */
    private fun parseEvent(text: String): ChatEvent? {
        val obj: Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            moshi.adapter(Map::class.java).fromJson(text) as? Map<String, Any?> ?: return null
        } catch (e: Exception) {
            return null
        }
        return when (obj["type"]) {
            EVENT_TYPE_MIDDLEMAN_LINE -> ChatEvent.MiddlemanLine(line = obj["line"] as? String ?: "")
            EVENT_TYPE_WORKER_LINE -> ChatEvent.WorkerLine(line = obj["line"] as? String ?: "")
            EVENT_TYPE_COMPLETE -> ChatEvent.Complete(summary = obj["summary"] as? String)
            EVENT_TYPE_APP_RELOAD -> ChatEvent.AppReload
            EVENT_TYPE_ERROR -> ChatEvent.Error(message = obj["message"] as? String ?: "unknown error")
            else -> null
        }
    }

    companion object {
        /**
         * Close code 1000 (normal closure) per
         * RFC 6455 §7.4.1. Used by [disconnect].
         */
        const val NORMAL_CLOSURE_CODE: Int = 1000

        /**
         * The `type` value the orchestrator's
         * `/chat` handler expects on the inbound
         * side. Mirrors the chat.py convention
         * (see also the `user_message` arm in
         * `handle_chat`).
         */
        const val USER_MESSAGE_TYPE: String = "user_message"

        /**
         * The `type` values the orchestrator
         * emits on the outbound side. Mirrors
         * the `WS_TYPE_*` constants in
         * `seed_backend/events.py`.
         */
        const val EVENT_TYPE_MIDDLEMAN_LINE: String = "middleman_line"
        const val EVENT_TYPE_WORKER_LINE: String = "worker_line"
        const val EVENT_TYPE_COMPLETE: String = "complete"
        const val EVENT_TYPE_APP_RELOAD: String = "app_reload"
        const val EVENT_TYPE_ERROR: String = "error"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // No read timeout for the WebSocket:
            // the server keeps the connection
            // open indefinitely between events,
            // and we want to receive the next
            // event whenever it arrives. The
            // ping interval (10s) is what the
            // RFC recommends and what OkHttp
            // uses by default.
            .pingInterval(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Simple exponential backoff for the WS
 * reconnect loop.
 *
 * Schedule (per attempt): 1s, 2s, 4s, 8s, 16s,
 * 30s (cap), 30s, 30s, ... No jitter — the
 * orchestrator is local, the failure mode is
 * "user rebooted the dev box", and jitter would
 * just delay recovery.
 *
 * Reset on a successful `onOpen` so the next
 * failure starts at 1s again.
 */
internal class ReconnectBackoff {
    private var attempt: Int = 0

    fun nextDelayMs(): Long {
        val delay = if (attempt < 5) 1_000L shl attempt else 30_000L
        attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }
}

/**
 * The inbound WS frame shape — a `user_message`
 * command the orchestrator's chat handler
 * expects. Kept separate from [ChatEvent] (the
 * outbound shape) so the wire formats can drift
 * in either direction without a ripple.
 */
@JsonClass(generateAdapter = false)
internal data class UserMessage(
    val type: String,
    val text: String,
)
