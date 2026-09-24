package com.seed.app.ui.chat

import com.seed.app.data.ChatEvent
import com.seed.app.data.ChatTransport
import com.seed.app.data.ChatWebSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    @Test fun `connection transitions and task status appear in debug history`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.state.value = ChatWebSocket.ConnectionState.CONNECTED
        fakeChat.state.value = ChatWebSocket.ConnectionState.RECONNECTING
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", "editing"))
        assertTrue(vm.debugMessages.value.any { it.contains("CONNECTED") })
        assertTrue(vm.debugMessages.value.any { it.contains("RECONNECTING") })
        assertTrue(vm.debugMessages.value.any { it.contains("task_status") && it.contains("running") })
    }

    @Test fun `debug output redacts secrets and omits provider dumps`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.WorkerLine("Authorization: Bearer secret-token"))
        fakeChat.emit(ChatEvent.WorkerLine("api_key=sk-secret123"))
        fakeChat.emit(ChatEvent.WorkerLine("provider request dump: secret-body"))
        val history = vm.debugMessages.value.joinToString(" ")
        assertTrue(!history.contains("secret-token"))
        assertTrue(!history.contains("sk-secret123"))
        assertTrue(!history.contains("secret-body"))
    }

    @Test fun `ack accepts pending message and clears only its original draft`() = runTest {
        fakeChat.autoAck = false
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("original")
        vm.send()
        assertEquals("original", vm.inputText.value)
        assertTrue(vm.sendPending.value)
        vm.onInputChange("new draft")
        fakeChat.emit(ChatEvent.UserAck(fakeChat.requestIds.single(), true, null))
        assertEquals("new draft", vm.inputText.value)
        assertTrue(!vm.sendPending.value)
        assertEquals("original", vm.messages.value.filterIsInstance<ChatMessage.User>().single().text)
    }

    @Test fun `rejected ack preserves draft and permits retry`() = runTest {
        fakeChat.autoAck = false
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("important prompt")
        vm.send()
        fakeChat.emit(ChatEvent.UserAck("wrong", true, null))
        assertTrue(vm.sendPending.value)
        fakeChat.emit(ChatEvent.UserAck(fakeChat.requestIds.single(), false, "busy"))
        assertEquals("important prompt", vm.inputText.value)
        assertTrue(!vm.sendPending.value)
        assertTrue(vm.messages.value.any { it is ChatMessage.System && it.summary?.contains("busy") == true })
        vm.send()
        assertEquals(2, fakeChat.sent.size)
    }

    @Test fun `reconnect leaves delivery unconfirmed and retry reuses id without duplicate bubble`() = runTest {
        fakeChat.autoAck = false
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("important")
        vm.send()
        val id = fakeChat.requestIds.single()
        fakeChat.state.value = ChatWebSocket.ConnectionState.RECONNECTING
        assertTrue(!vm.sendPending.value)
        assertEquals("important", vm.inputText.value)
        assertTrue(vm.messages.value.none { it is ChatMessage.User })
        assertTrue(vm.messages.value.any { it is ChatMessage.System && it.summary ==
            "Delivery unconfirmed; message may have been accepted. Check chat before retrying" })
        vm.send()
        assertEquals(listOf(id, id), fakeChat.requestIds)
        fakeChat.emit(ChatEvent.UserAck(id, true, null))
        assertTrue(!vm.sendPending.value)
        assertEquals(1, vm.messages.value.filterIsInstance<ChatMessage.User>().size)
    }

    @Test fun `timeout preserves draft and late ack resolves retry safely`() = runTest {
        fakeChat.autoAck = false
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("important")
        vm.send()
        val id = fakeChat.requestIds.single()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(!vm.sendPending.value)
        assertEquals("important", vm.inputText.value)
        vm.send()
        assertEquals(listOf(id, id), fakeChat.requestIds)
        fakeChat.emit(ChatEvent.UserAck(id, true, null))
        fakeChat.emit(ChatEvent.UserAck(id, true, null))
        assertTrue(!vm.sendPending.value)
        assertEquals(1, vm.messages.value.filterIsInstance<ChatMessage.User>().size)
    }

    @Test fun `late accepted A does not disturb pending B and B still accepts`() = runTest {
        fakeChat.autoAck = false
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("A")
        vm.send()
        val a = fakeChat.requestIds.last()
        advanceTimeBy(10_001)
        runCurrent()
        vm.onInputChange("B")
        vm.send()
        val b = fakeChat.requestIds.last()
        assertTrue(a != b)
        fakeChat.emit(ChatEvent.UserAck(a, true, null))
        assertEquals("B", vm.inputText.value)
        assertTrue(vm.sendPending.value)
        assertEquals(listOf("A"), vm.messages.value.filterIsInstance<ChatMessage.User>().map { it.text })
        fakeChat.emit(ChatEvent.UserAck(b, true, null))
        assertEquals("", vm.inputText.value)
        assertTrue(!vm.sendPending.value)
        assertEquals(listOf("A", "B"), vm.messages.value.filterIsInstance<ChatMessage.User>().map { it.text })
    }

    @Test fun `late accepted A leaves B timeout and rejection draft intact`() = runTest {
        fakeChat.autoAck = false
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("A")
        vm.send()
        val a = fakeChat.requestIds.last()
        advanceTimeBy(10_001)
        runCurrent()
        vm.onInputChange("B")
        vm.send()
        val b = fakeChat.requestIds.last()
        fakeChat.emit(ChatEvent.UserAck(a, true, null))
        fakeChat.emit(ChatEvent.UserAck(b, false, "busy"))
        assertEquals("B", vm.inputText.value)
        assertTrue(!vm.sendPending.value)
        assertEquals(listOf("A"), vm.messages.value.filterIsInstance<ChatMessage.User>().map { it.text })
        assertTrue(vm.messages.value.any { it is ChatMessage.System && it.summary?.contains("busy") == true })
    }

    @Test fun `disconnected send immediately reports failure`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.sendAccepted = false
        vm.onInputChange("hello")
        vm.send()
        assertEquals("hello", vm.inputText.value)
        assertTrue(vm.messages.value.none { it is ChatMessage.User })
        assertEquals(SystemEventKind.ERROR, (vm.messages.value.last() as ChatMessage.System).kind)
    }

    @Test fun `history gap restores task and outcome together without allowing stale status`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", "old"))
        fakeChat.emit(ChatEvent.HistoryGap("expired", ChatEvent.TaskStatus("task-1", "running", "working"),
            ChatEvent.TaskOutcome("task-1", "completed", "final", "worker")))
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", "delayed"))
        val card = vm.messages.value.filterIsInstance<ChatMessage.Task>().single()
        assertEquals("completed", card.status)
        assertEquals("final", card.summary)
        assertEquals("completed", vm.taskStatus.value?.status)
    }

    @Test fun `terminal outcome is rendered once despite replay`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        val outcome = ChatEvent.TaskOutcome("task-1", "completed", "Done", "backend")
        fakeChat.emit(outcome)
        fakeChat.emit(outcome)
        assertEquals(1, vm.messages.value.filterIsInstance<ChatMessage.Task>().size)
    }

    @Test fun `terminal snapshot is enriched once by outcome and cannot regress`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", "working"))
        fakeChat.emit(ChatEvent.TaskOutcome("task-1", "completed", null, null))
        fakeChat.emit(ChatEvent.TaskOutcome("task-1", "completed", "Final summary", "worker"))
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", "stale"))
        fakeChat.emit(ChatEvent.TaskOutcome("task-1", "completed", "Final summary", "worker"))
        val cards = vm.messages.value.filterIsInstance<ChatMessage.Task>()
        assertEquals(1, cards.size)
        assertEquals("completed", cards.single().status)
        assertEquals("Final summary", cards.single().summary)
        assertEquals("worker", cards.single().source)
        assertEquals("completed", vm.taskStatus.value?.status)
    }

    @Test fun `streamed middleman chunks form one bubble across reconnect and filtered dispatch`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.MiddlemanLine("Hel"))
        fakeChat.state.value = ChatWebSocket.ConnectionState.RECONNECTING
        fakeChat.emit(ChatEvent.MiddlemanLine("lo\n```json\n{\"worker\":\"build\"}\n```\n"))
        fakeChat.emit(ChatEvent.MiddlemanLine("Done"))
        val replies = vm.messages.value.filterIsInstance<ChatMessage.Agent>()
        assertEquals(1, replies.size)
        assertEquals("Hello\nDone", replies.single().text)
    }

    @Test fun `user message and task card split assistant turns`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.MiddlemanLine("first"))
        vm.onInputChange("next")
        vm.send()
        fakeChat.emit(ChatEvent.MiddlemanLine("second"))
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", null))
        fakeChat.emit(ChatEvent.MiddlemanLine("third"))
        assertEquals(listOf("first", "second", "third"),
            vm.messages.value.filterIsInstance<ChatMessage.Agent>().map { it.text })
        assertEquals(5, vm.messages.value.size)
    }

    @Test fun `role availability is independent of task progress`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.RoleHealth("worker", "unavailable"))
        assertEquals("unavailable", vm.roleHealth.value["worker"])
    }

    @Test fun `status snapshot drives work indicator and stop is direct`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", "editing"))
        assertEquals("running", vm.taskStatus.value?.status)
        vm.stopTask()
        assertEquals(1, fakeChat.stopCalls)
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "completed", "done"))
        assertEquals("completed", vm.taskStatus.value?.status)
    }

    @Test fun `dispatch and worker output stay out of normal chat`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.MiddlemanLine("I'll handle this\n"))
        fakeChat.emit(ChatEvent.MiddlemanLine("```json\n"))
        fakeChat.emit(ChatEvent.MiddlemanLine("{\"worker\":\"build\"}\n"))
        fakeChat.emit(ChatEvent.MiddlemanLine("```"))
        fakeChat.emit(ChatEvent.WorkerLine("tool output"))
        fakeChat.emit(ChatEvent.Complete("worker report"))
        fakeChat.emit(ChatEvent.MiddlemanLine("Finished the change"))
        fakeChat.emit(ChatEvent.Error("problem"))
        assertEquals(listOf("I'll handle this\nFinished the change", "problem"),
            vm.messages.value.map { when (it) {
                is ChatMessage.Agent -> it.text
                is ChatMessage.System -> it.summary
                is ChatMessage.User -> it.text
                is ChatMessage.Task -> it.summary
            } })
        assertTrue(vm.debugMessages.value.joinToString("").contains("\"worker\""))
        assertTrue(vm.debugMessages.value.contains("tool output"))
    }

    @Test fun `split json dispatch fence hides payload while preserving adjacent prose`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        listOf("I can do that.\n`", "``js", "on\n{\"worker\":", "\"build\"}\n`", "``\nDone.")
            .forEach { fakeChat.emit(ChatEvent.MiddlemanLine(it)) }
        assertEquals("I can do that.\nDone.", vm.messages.value.filterIsInstance<ChatMessage.Agent>().joinToString("") { it.text })
        assertTrue(vm.debugMessages.value.joinToString("").contains("\"worker\""))
    }

    @Test fun `ordinary braces and non-json fences remain visible`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        listOf("{hello}\n", "```kotlin\n", "{ code }\n", "```\n")
            .forEach { fakeChat.emit(ChatEvent.MiddlemanLine(it)) }
        assertEquals("{hello}\n```kotlin\n{ code }\n```\n",
            vm.messages.value.filterIsInstance<ChatMessage.Agent>().joinToString("") { it.text })
    }

    @Test fun `debug history is bounded for worker output and large hidden dispatch`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        repeat(200) { fakeChat.emit(ChatEvent.WorkerLine("x".repeat(2000))) }
        fakeChat.emit(ChatEvent.MiddlemanLine("```json\n" + "y".repeat(200_000) + "\n```\nVisible"))
        assertTrue(vm.debugMessages.value.size <= 100)
        assertTrue(vm.debugMessages.value.sumOf { it.length } <= 51_200)
        assertEquals("Visible", vm.messages.value.filterIsInstance<ChatMessage.Agent>().joinToString("") { it.text })
    }

    @Test fun `cancel pending status blocks duplicate stop`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "cancel_pending", null))
        assertEquals("cancel_pending", vm.taskStatus.value?.status)
        vm.stopTask()
        assertEquals(0, fakeChat.stopCalls)
    }

    @Test fun `failed stop reports error without changing authoritative running status`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.TaskStatus("task-1", "running", null))
        fakeChat.stopAccepted = false
        vm.stopTask()
        assertEquals(1, fakeChat.stopCalls)
        assertEquals("running", vm.taskStatus.value?.status)
        assertEquals("Could not stop task: disconnected. Retry when connected.",
            (vm.messages.value.last() as ChatMessage.System).summary)
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

    @Test fun `worker and legacy complete events are not normal replies`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        fakeChat.emit(ChatEvent.WorkerLine("editing app.py"))
        fakeChat.emit(ChatEvent.Complete("worker report"))
        assertEquals(emptyList<ChatMessage>(), vm.messages.value)
        assertTrue(vm.debugMessages.value.contains("editing app.py"))
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

    @Test fun `user and middleman replies remain ordered`() = runTest {
        val vm = ChatViewModel(chat = fakeChat)
        vm.onInputChange("add a habit tracker")
        vm.send()
        fakeChat.emit(ChatEvent.MiddlemanLine("what columns?"))
        fakeChat.emit(ChatEvent.WorkerLine("creating schema"))
        fakeChat.emit(ChatEvent.Complete("done"))
        assertEquals(2, vm.messages.value.size)
        assertTrue(vm.messages.value[0] is ChatMessage.User)
        assertEquals(AgentRole.MIDDLEMAN, (vm.messages.value[1] as ChatMessage.Agent).role)
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
    var stopCalls = 0
    var stopAccepted = true
    var sendAccepted = true
    var autoAck = true
    val requestIds = mutableListOf<String>()
    override val state = MutableStateFlow(ChatWebSocket.ConnectionState.DISCONNECTED)
    override fun stopTask(): Boolean { stopCalls++; return stopAccepted }

    private val _events = MutableSharedFlow<ChatEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    override fun connect() {
        connectCalled = true
    }

    override fun send(text: String, requestId: String): Boolean {
        requestIds.add(requestId)
        sent.add(text)
        if (sendAccepted && autoAck) emit(ChatEvent.UserAck(requestId, true, null))
        // We pretend the send always succeeds;
        // the ViewModel doesn't use the return
        // value for v0.1 (a future task may).
        return sendAccepted
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
