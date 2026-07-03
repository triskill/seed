package com.seed.app.ui.chat

import com.seed.app.data.ChatEvent
import com.seed.app.data.ChatTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ChatViewModel] state
 * transitions.
 *
 * The ViewModel is plain Kotlin (no Android
 * imports other than `ViewModel` from the
 * lifecycle artifact, which is JVM-testable
 * via the same dependency tree the app uses
 * at runtime).
 *
 * **Phase 5.4** shipped the ViewModel in a
 * "local-only" configuration: it accepted
 * text and appended user messages, but didn't
 * talk to the backend yet.
 *
 * **Phase 6.3** wires the ViewModel to a
 * [ChatTransport]. The tests use
 * [FakeChatTransport] — a hand-rolled
 * controllable stub — so we don't need a
 * real WebSocket. The fake captures outbound
 * `send` calls and lets the test emit canned
 * [ChatEvent]s into the flow the ViewModel
 * is collecting.
 *
 * **Test dispatcher:** the ViewModel's
 * `viewModelScope` uses `Dispatchers.Main` (the
 * Android default), so the collector launched
 * in `init` would normally run on the main
 * thread. In unit tests we replace it with
 * `UnconfinedTestDispatcher` in `@Before` (and
 * restore the real Main in `@After`) so the
 * `viewModelScope.launch { chat.events.collect
 * { ... } }` body runs eagerly — the test can
 * emit an event and immediately see the
 * translated message in [ChatViewModel.messages]
 * without `advanceUntilIdle()` plumbing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private lateinit var fakeChat: FakeChatTransport

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeChat = FakeChatTransport()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Phase 5.4 — local-only behavior -----------------------

    @Test
    fun `initial state is empty messages and empty input`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        assertEquals(emptyList<ChatMessage>(), vm.messages.value)
        assertEquals("", vm.inputText.value)
    }

    @Test
    fun `onInputChange updates inputText`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("hello")
        assertEquals("hello", vm.inputText.value)
    }

    @Test
    fun `send appends a user message`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("hello")
        vm.send()

        val messages = vm.messages.value
        assertEquals(1, messages.size)
        val msg = messages[0]
        assertTrue("expected User, got $msg", msg is ChatMessage.User)
        assertEquals("hello", (msg as ChatMessage.User).text)
    }

    @Test
    fun `send clears the input after sending`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("hello")
        vm.send()
        assertEquals("", vm.inputText.value)
    }

    @Test
    fun `send with empty input does nothing`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.send()
        assertEquals(emptyList<ChatMessage>(), vm.messages.value)
        // No outbound WS frame should have been
        // sent.
        assertEquals(emptyList<String>(), fakeChat.sent)
    }

    @Test
    fun `send with whitespace-only input does not append but preserves input`() = runTest {
        // Whitespace-only input is invalid (nothing
        // meaningful to send) but the user might be
        // mid-typing; we should not yank their text.
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("   \n\t  ")
        vm.send()
        assertEquals(emptyList<ChatMessage>(), vm.messages.value)
        assertEquals("   \n\t  ", vm.inputText.value)
        assertEquals(emptyList<String>(), fakeChat.sent)
    }

    @Test
    fun `send appends multiple messages in order`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("first")
        vm.send()
        vm.onInputChange("second")
        vm.send()
        vm.onInputChange("third")
        vm.send()

        val messages = vm.messages.value
        assertEquals(3, messages.size)
        assertEquals("first", (messages[0] as ChatMessage.User).text)
        assertEquals("second", (messages[1] as ChatMessage.User).text)
        assertEquals("third", (messages[2] as ChatMessage.User).text)
    }

    @Test
    fun `send trims leading and trailing whitespace before storing`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("  hello world  ")
        vm.send()

        val messages = vm.messages.value
        assertEquals(1, messages.size)
        // Trimmed so the bubble doesn't have phantom
        // leading/trailing spaces.
        assertEquals("hello world", (messages[0] as ChatMessage.User).text)
    }

    // ---- Phase 6.3 — backend wiring ----------------------------

    @Test
    fun `init calls connect on the transport`() = runTest {
        ChatViewModel(chat = fakeChat)
        assertTrue("connect() should have been called", fakeChat.connectCalled)
    }

    @Test
    fun `send forwards the trimmed text to the transport`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("  hello world  ")
        vm.send()
        // The user bubble shows the trimmed text
        // (asserted elsewhere); the WS frame
        // also carries the trimmed text — the
        // middle-man / worker would see the
        // same string the user sees in their
        // own bubble.
        assertEquals(listOf("hello world"), fakeChat.sent)
    }

    @Test
    fun `send with no transport forward when input is empty`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.send()
        assertEquals(emptyList<String>(), fakeChat.sent)
    }

    @Test
    fun `MiddlemanLine event becomes an Agent bubble`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.MiddlemanLine(line = "thinking about it"))
        val messages = vm.messages.value
        assertEquals(1, messages.size)
        val msg = messages[0]
        assertTrue("expected Agent, got $msg", msg is ChatMessage.Agent)
        val agent = msg as ChatMessage.Agent
        assertEquals(AgentRole.MIDDLEMAN, agent.role)
        assertEquals("thinking about it", agent.text)
    }

    @Test
    fun `WorkerLine event becomes an Agent bubble`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.WorkerLine(line = "editing app.py"))
        val messages = vm.messages.value
        assertEquals(1, messages.size)
        val msg = messages[0] as ChatMessage.Agent
        assertEquals(AgentRole.WORKER, msg.role)
        assertEquals("editing app.py", msg.text)
    }

    @Test
    fun `Complete event becomes a System COMPLETE banner`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.Complete(summary = "added /hello route"))
        val messages = vm.messages.value
        assertEquals(1, messages.size)
        val msg = messages[0] as ChatMessage.System
        assertEquals(SystemEventKind.COMPLETE, msg.kind)
        assertEquals("added /hello route", msg.summary)
    }

    @Test
    fun `Complete event with null summary still becomes a System COMPLETE banner`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.Complete(summary = null))
        val messages = vm.messages.value
        val msg = messages[0] as ChatMessage.System
        assertEquals(SystemEventKind.COMPLETE, msg.kind)
        assertEquals(null, msg.summary)
    }

    @Test
    fun `AppReload event becomes a System APP_RELOAD banner`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.AppReload)
        val messages = vm.messages.value
        val msg = messages[0] as ChatMessage.System
        assertEquals(SystemEventKind.APP_RELOAD, msg.kind)
    }

    @Test
    fun `Error event becomes a System ERROR banner with the message as summary`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.Error(message = "agent crashed"))
        val messages = vm.messages.value
        val msg = messages[0] as ChatMessage.System
        assertEquals(SystemEventKind.ERROR, msg.kind)
        assertEquals("agent crashed", msg.summary)
    }

    @Test
    fun `events accumulate in order alongside user messages`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("add a habit tracker")
        vm.send()
        fakeChat.emit(ChatEvent.MiddlemanLine(line = "what columns?"))
        fakeChat.emit(ChatEvent.WorkerLine(line = "creating schema"))
        fakeChat.emit(ChatEvent.Complete(summary = "done"))
        fakeChat.emit(ChatEvent.AppReload)
        val messages = vm.messages.value
        assertEquals(5, messages.size)
        assertTrue(messages[0] is ChatMessage.User)
        assertEquals(AgentRole.MIDDLEMAN, (messages[1] as ChatMessage.Agent).role)
        assertEquals(AgentRole.WORKER, (messages[2] as ChatMessage.Agent).role)
        assertEquals(SystemEventKind.COMPLETE, (messages[3] as ChatMessage.System).kind)
        assertEquals(SystemEventKind.APP_RELOAD, (messages[4] as ChatMessage.System).kind)
    }
}

/**
 * Hand-rolled [ChatTransport] for unit tests.
 *
 * Captures every outbound `send` call into
 * [sent] (a list, so multiple sends in one
 * test are preserved in order) and lets the
 * test emit canned [ChatEvent]s into [emit].
 *
 * The [events] flow is a [MutableSharedFlow]
 * with the same configuration as the
 * production [com.seed.app.data.ChatWebSocket]:
 * 0 replay, 64-slot buffer, DROP_OLDEST.
 * Tests don't need the buffer; the small
 * bursts they emit (≤ 5 events) always fit.
 */
class FakeChatTransport : ChatTransport {
    var connectCalled: Boolean = false
    var closeCalled: Boolean = false
    val sent: MutableList<String> = mutableListOf()

    private val _events = MutableSharedFlow<ChatEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    override fun connect() {
        connectCalled = true
    }

    override fun send(text: String): Boolean {
        sent.add(text)
        // We pretend the send always succeeds;
        // the ViewModel doesn't use the return
        // value for v0.1 (a future task may).
        return true
    }

    override fun close() {
        closeCalled = true
    }

    /**
     * Test helper: synchronously push one
     * [ChatEvent] into [events]. Uses
     * [MutableSharedFlow.tryEmit] (not the
     * suspending `emit`) so tests don't need
     * to launch a coroutine to feed events.
     */
    fun emit(event: ChatEvent) {
        _events.tryEmit(event)
    }
}
