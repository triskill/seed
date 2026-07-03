package com.seed.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Contract tests for [ChatWebSocket] using
 * MockWebServer's WebSocket support.
 *
 * **What this verifies:**
 *   - The lifecycle: initial DISCONNECTED, then
 *     CONNECTING → CONNECTED on `connect()`,
 *     back to DISCONNECTED on `disconnect()`.
 *   - The `send` path: a `user_message` frame
 *     with the right JSON shape reaches the
 *     server-side handler.
 *   - The `events` path: every WS frame the
 *     server sends is parsed into the right
 *     [ChatEvent] variant and emitted to
 *     subscribers.
 *   - The reconnect path: a server-initiated
 *     close triggers a backoff + retry; the
 *     second connection is functional.
 *   - `send` returns `false` (and doesn't throw)
 *     when the WebSocket is not connected.
 *
 * **What this does NOT verify:**
 *   - Backoff timing precision. The schedule
 *     (1s, 2s, 4s, ...) is tested in
 *     [ReconnectBackoffTest] as a unit test of
 *     the helper. The integration test here
 *     just waits long enough for the retry to
 *     happen and verifies the second
 *     connection succeeds.
 *   - Real-network behavior. MockWebServer
 *     is on localhost; the connection drops
 *     simulated by `SocketPolicy` are not
 *     identical to a real network failure.
 */
class ChatWebSocketTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var chat: ChatWebSocket

    // Held by the test so the @After can cancel
    // any collector coroutines we launch (each
    // `runTest`-style `launch` would otherwise
    // leak past the test's end).
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Short timeouts so a hung test fails
        // fast. The WebSocket itself doesn't
        // have a read timeout (the orchestrator
        // keeps the connection open between
        // events), but the connect / write
        // timeouts still apply.
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
        chat = ChatWebSocket(baseUrl = server.url("/").toString(), client = client)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        chat.close()
        // `chat.close()` cancels the
        // reconnect loop, closes the
        // WebSocket, and tears down the
        // internal coroutine scope. The
        // close handshake is asynchronous
        // and the MockWebServer's
        // per-connection task queue needs
        // a moment to drain. A 500ms delay
        // is generous on localhost (the
        // close handshake is typically
        // <50ms).
        runBlocking { delay(500) }
        // MockWebServer 4.12.0's shutdown
        // throws "Gave up waiting for
        // queue to shut down" if a
        // WebSocket's close handshake
        // isn't fully drained; this is a
        // known interop quirk with OkHttp
        // 4.x's WebSocket. The test body
        // itself has already passed, so
        // we swallow the IOException and
        // move on.
        try {
            server.shutdown()
        } catch (e: java.io.IOException) {
            // MockWebServer interop quirk
            // (see above). The test passed;
            // we're done.
        }
    }

    // ---- Lifecycle -----------------------------------------------

    @Test
    fun `initial state is DISCONNECTED`() = runBlocking {
        assertEquals(ChatWebSocket.ConnectionState.DISCONNECTED, chat.state.value)
    }

    @Test
    fun `connect transitions to CONNECTED`() = runBlocking {
        server.enqueue(noopUpgrade())

        chat.connect()
        withTimeout(2_000) {
            chat.state.first { it == ChatWebSocket.ConnectionState.CONNECTED }
        }
        assertEquals(ChatWebSocket.ConnectionState.CONNECTED, chat.state.value)
    }

    @Test
    fun `disconnect transitions back to DISCONNECTED`() = runBlocking {
        server.enqueue(noopUpgrade())
        chat.connect()
        withTimeout(2_000) {
            chat.state.first { it == ChatWebSocket.ConnectionState.CONNECTED }
        }

        chat.disconnect()
        withTimeout(2_000) {
            chat.state.first { it == ChatWebSocket.ConnectionState.DISCONNECTED }
        }
        assertEquals(ChatWebSocket.ConnectionState.DISCONNECTED, chat.state.value)
    }

    @Test
    fun `connect is idempotent while a loop is already running`() = runBlocking {
        server.enqueue(noopUpgrade())
        chat.connect()
        withTimeout(2_000) {
            chat.state.first { it == ChatWebSocket.ConnectionState.CONNECTED }
        }

        // Second connect() should be a no-op:
        // state stays CONNECTED, and we don't
        // open a second WebSocket on the server
        // side (the request count stays at 1).
        chat.connect()
        delay(200)
        assertEquals(ChatWebSocket.ConnectionState.CONNECTED, chat.state.value)
        assertEquals("no extra request was sent", 1, server.requestCount)
    }

    // ---- send() --------------------------------------------------

    @Test
    fun `send writes a user_message JSON frame to the server`() = runBlocking {
        val received = mutableListOf<String>()
        val opened = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(Unit)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.add(text)
                    }
                },
            ),
        )
        chat.connect()
        withTimeout(2_000) { opened.await() }
        // Yield so the onOpen listener completes
        // on the server side before we send.
        delay(50)

        val accepted = chat.send("hello world")
        assertTrue("send should report accepted", accepted)
        // Wait briefly for the server handler
        // to record the message.
        withTimeout(2_000) {
            while (received.isEmpty()) delay(20)
        }
        assertEquals(
            "expected a single user_message frame",
            listOf("""{"type":"user_message","text":"hello world"}"""),
            received,
        )
    }

    @Test
    fun `send returns false when not connected`() = runBlocking {
        // No server.enqueue(wsUpgrade()) — the
        // client has nothing to talk to.
        val accepted = chat.send("nobody home")
        assertFalse("send should report not accepted", accepted)
    }

    @Test
    fun `send JSON-escapes quotes and newlines in the text`() = runBlocking {
        val received = mutableListOf<String>()
        val opened = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(Unit)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.add(text)
                    }
                },
            ),
        )
        chat.connect()
        withTimeout(2_000) { opened.await() }
        delay(50)

        chat.send("""he said "hi"
and left""")
        withTimeout(2_000) {
            while (received.isEmpty()) delay(20)
        }
        // Verify byte-for-byte: Moshi escapes
        // the inner double-quotes (\\" in the
        // JSON) and the newline (\\n). This
        // guards against accidentally rolling
        // our own JSON serialization.
        assertEquals(
            listOf(
                """{"type":"user_message","text":"he said \"hi\"\nand left"}""",
            ),
            received,
        )
    }

    // ---- events() parsing ----------------------------------------

    @Test
    fun `events flow emits MiddlemanLine for middleman_line frames`() = runBlocking {
        val events = mutableListOf<ChatEvent>()
        val collector = testScope.launch {
            chat.events.collect { events.add(it) }
        }
        val serverSocket = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.complete(webSocket)
                    }
                },
            ),
        )
        chat.connect()
        withTimeout(2_000) {
            chat.state.first { it == ChatWebSocket.ConnectionState.CONNECTED }
        }
        // Push a frame as the server.
        val socket = withTimeout(2_000) { serverSocket.await() }
        socket.send("""{"type":"middleman_line","line":"hello from middleman"}""")
        withTimeout(2_000) {
            while (events.isEmpty()) delay(20)
        }
        assertEquals(1, events.size)
        val event = events[0]
        assertTrue("expected MiddlemanLine, got $event", event is ChatEvent.MiddlemanLine)
        assertEquals("hello from middleman", (event as ChatEvent.MiddlemanLine).line)
        collector.cancel()
    }

    @Test
    fun `events flow emits WorkerLine, Complete, AppReload, and Error`() = runBlocking {
        val events = mutableListOf<ChatEvent>()
        val collector = testScope.launch {
            chat.events.collect { events.add(it) }
        }
        val serverSocket = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.complete(webSocket)
                    }
                },
            ),
        )
        chat.connect()
        withTimeout(2_000) {
            chat.state.first { it == ChatWebSocket.ConnectionState.CONNECTED }
        }
        val socket = withTimeout(2_000) { serverSocket.await() }
        // Rapid burst — the production code
        // uses `tryEmit` (not the suspending
        // `emit`) on the SharedFlow, which is
        // order-preserving across concurrent
        // listeners, so the chat UI sees the
        // events in the order the server sent
        // them.
        socket.send("""{"type":"worker_line","line":"building..."}""")
        socket.send("""{"type":"complete","summary":"added /hello route"}""")
        socket.send("""{"type":"app_reload"}""")
        socket.send("""{"type":"error","message":"agent crashed"}""")
        withTimeout(2_000) {
            while (events.size < 4) delay(20)
        }
        assertEquals(4, events.size)
        assertTrue(events[0] is ChatEvent.WorkerLine)
        assertEquals("building...", (events[0] as ChatEvent.WorkerLine).line)
        assertTrue(events[1] is ChatEvent.Complete)
        assertEquals("added /hello route", (events[1] as ChatEvent.Complete).summary)
        assertEquals(ChatEvent.AppReload, events[2])
        assertTrue(events[3] is ChatEvent.Error)
        assertEquals("agent crashed", (events[3] as ChatEvent.Error).message)
        collector.cancel()
    }

    @Test
    fun `events flow ignores frames with unknown type`() = runBlocking {
        val events = mutableListOf<ChatEvent>()
        val collector = testScope.launch {
            chat.events.collect { events.add(it) }
        }
        val serverSocket = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.complete(webSocket)
                    }
                },
            ),
        )
        chat.connect()
        withTimeout(2_000) {
            chat.state.first { it == ChatWebSocket.ConnectionState.CONNECTED }
        }
        val socket = withTimeout(2_000) { serverSocket.await() }
        socket.send("""{"type":"future_event","payload":"v0.2"}""")
        socket.send("""{"type":"middleman_line","line":"ok"}""")
        withTimeout(2_000) {
            while (events.isEmpty()) delay(20)
        }
        // The unknown-type frame is dropped
        // silently (the orchestrator may add
        // new event kinds in later phases;
        // the Kotlin side tolerates the
        // forward-compat by ignoring unknowns).
        assertEquals(1, events.size)
        assertTrue(events[0] is ChatEvent.MiddlemanLine)
        collector.cancel()
    }

    // ---- Reconnect -----------------------------------------------

    @Test
    fun `reconnect after server-initiated close`() = runBlocking {
        // First connection: server closes on
        // any inbound text. We capture the
        // server-side socket in onOpen so we
        // can trigger the close.
        val firstOpened = CompletableDeferred<Unit>()
        val firstClose = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstOpened.complete(Unit)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.close(1000, "bye")
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        firstClose.complete(Unit)
                    }
                },
            ),
        )
        // Second connection: server stays
        // open and accepts a message.
        val secondOpened = CompletableDeferred<Unit>()
        val secondReceived = mutableListOf<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        secondOpened.complete(Unit)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        secondReceived.add(text)
                    }
                },
            ),
        )

        chat.connect()
        withTimeout(2_000) { firstOpened.await() }
        // Trigger the server-side close by
        // sending a message (the handler
        // closes the WebSocket on any inbound
        // text).
        chat.send("trigger")
        withTimeout(2_000) { firstClose.await() }

        // The client should reconnect within
        // the 1s initial backoff. We wait
        // long enough for the second
        // onOpen to fire (1s backoff + a
        // little handshake slack).
        withTimeout(3_000) { secondOpened.await() }

        // The second connection is
        // functional: send works through it.
        chat.send("after reconnect")
        withTimeout(2_000) {
            while (secondReceived.isEmpty()) delay(20)
        }
        assertEquals(listOf("""{"type":"user_message","text":"after reconnect"}"""), secondReceived)
    }

    @Test
    fun `state reaches RECONNECTING after a failure and a backoff`() = runBlocking {
        // The simplest way to force onFailure
        // is to enqueue a response that
        // disconnects the socket. The client's
        // OkHttp handshake will fail; the
        // reconnect loop will back off and
        // sleep, leaving the state machine
        // in RECONNECTING.
        server.enqueue(
            MockResponse().setSocketPolicy(
                okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START,
            ),
        )

        chat.connect()
        withTimeout(2_000) {
            // The first attempt happens
            // immediately. We may briefly see
            // CONNECTING; the loop is in
            // RECONNECTING for the rest of the
            // 1s backoff.
            chat.state.first {
                it == ChatWebSocket.ConnectionState.RECONNECTING
            }
        }
        assertEquals(
            "expected to be in RECONNECTING after backoff",
            ChatWebSocket.ConnectionState.RECONNECTING,
            chat.state.value,
        )
    }

    // ---- Helpers -------------------------------------------------

    /**
     * A no-op WebSocket upgrade — the server
     * accepts the handshake, doesn't send
     * anything, and stays open. Useful for
     * lifecycle tests that just need the
     * client to reach CONNECTED.
     */
    private fun noopUpgrade(): MockResponse = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit
        },
    )
}

/**
 * Unit tests for the [ReconnectBackoff] helper.
 * The integration tests in
 * [ChatWebSocketTest] only verify that a
 * reconnect *happens* — these pin the exact
 * schedule (1s, 2s, 4s, 8s, 16s, 30s, 30s, ...)
 * so a future "let's add jitter" or "let's
 * cap at 60s" change shows up here first.
 */
class ReconnectBackoffTest {

    @Test
    fun `first five attempts double the delay`() {
        val backoff = ReconnectBackoff()
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(4_000L, backoff.nextDelayMs())
        assertEquals(8_000L, backoff.nextDelayMs())
        assertEquals(16_000L, backoff.nextDelayMs())
    }

    @Test
    fun `attempts beyond the fifth stay at the 30s cap`() {
        val backoff = ReconnectBackoff()
        // Burn the first five (1s..16s).
        repeat(5) { backoff.nextDelayMs() }
        assertEquals(30_000L, backoff.nextDelayMs())
        assertEquals(30_000L, backoff.nextDelayMs())
        assertEquals(30_000L, backoff.nextDelayMs())
    }

    @Test
    fun `reset returns to the initial 1s delay`() {
        val backoff = ReconnectBackoff()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }
}
