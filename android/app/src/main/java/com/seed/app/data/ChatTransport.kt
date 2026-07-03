package com.seed.app.data

import kotlinx.coroutines.flow.SharedFlow

/**
 * The narrow contract the ChatViewModel
 * (Phase 6.3) needs from the WebSocket layer.
 *
 * **Why an interface (not [ChatWebSocket] directly):**
 * unit tests need a controllable fake that emits
 * canned [ChatEvent]s and captures outbound
 * `send` calls. The real [ChatWebSocket] would
 * open a real socket against a (non-existent)
 * backend, which is impossible on the JVM unit
 * test classpath. Extracting the surface into an
 * interface is the standard seam: the production
 * class implements it, and the test fakes it.
 *
 * **Why the surface is so small:** the
 * ChatViewModel only needs to (1) start the
 * connection, (2) send user messages, (3)
 * collect [ChatEvent]s. State ([ChatWebSocket.state])
 * is a UI concern (a "Reconnecting..." pill in
 * Phase 6.3+); the ViewModel doesn't need it for
 * its core logic.
 *
 * The `events` flow is a [SharedFlow] (not a
 * cold `Flow`) because the consumer (the
 * ViewModel) needs a single subscription shared
 * across the ViewModel's lifetime, and the
 * production impl uses a [SharedFlow] internally
 * for its backpressure behavior (DROP_OLDEST).
 */
interface ChatTransport {

    /**
     * Start the connection. Idempotent: a second
     * call while the transport is already running
     * is a no-op. The transport's `events` flow
     * starts emitting once the underlying socket
     * is open.
     */
    fun connect()

    /**
     * Send a `user_message` frame. Returns
     * `true` if the transport accepted the
     * frame, `false` if it's not currently
     * connected (the ViewModel can use this
     * to show a "Reconnecting..." affordance
     * or buffer the message for later).
     */
    fun send(text: String): Boolean

    /**
     * The hot stream of [ChatEvent]s the
     * orchestrator broadcasts. Multiple
     * collectors are allowed; each event is
     * delivered to every active collector
     * exactly once (no replay).
     */
    val events: SharedFlow<ChatEvent>

    /**
     * Tear the transport down completely:
     * stop the connection loop, close the
     * socket, and cancel the internal
     * coroutine scope. After [close] the
     * transport is unusable — the ViewModel
     * calls this from `onCleared` to ensure
     * no background work outlives the screen.
     */
    fun close()
}
