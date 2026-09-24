package com.seed.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seed.app.BuildConfig
import com.seed.app.data.ChatEvent
import com.seed.app.data.ChatTransport
import com.seed.app.data.ChatWebSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Chat tab.
 *
 * **Phase 5.4** shipped a local-only ViewModel:
 * it appended user-typed messages to a
 * [StateFlow] and cleared the input field. No
 * backend connection — the user could type, see
 * their own message appear, and verify the
 * wiring works.
 *
 * **Phase 6.3** wires the ViewModel to the
 * backend via a [ChatTransport]:
 *   - the constructor now takes a
 *     [ChatTransport] (default = a real
 *     [ChatWebSocket] bound to
 *     [BuildConfig.BACKEND_DEV_URL]);
 *   - on init, the ViewModel calls
 *     [ChatTransport.connect] and launches a
 *     collector in [viewModelScope] that
 *     translates [ChatEvent]s into the
 *     [ChatMessage] sealed class the Compose
 *     layer already renders;
 *   - [send] now also calls [ChatTransport.send]
 *     to push the user message over the WS;
 *   - [onCleared] tears the transport down so
 *     the background connection loop doesn't
 *     outlive the screen.
 *
 * The public surface ([messages], [inputText],
 * [onInputChange], [send]) is the same shape
 * as Phase 5.4 — the Compose screen doesn't
 * need to change. The screen continues to
 * read [messages] for the [LazyColumn] and
 * [inputText] for the `TextField`.
 *
 * **Why the default constructor builds a real
 * [ChatWebSocket]:** the Compose `viewModel<ChatViewModel>()`
 * helper constructs the ViewModel with the
 * no-arg overload, so production code gets a
 * working backend connection for free. Unit
 * tests pass an explicit [ChatTransport] fake
 * (see [com.seed.app.ui.chat.FakeChatTransport])
 * and never hit the default — so the
 * `BuildConfig` reference in the default
 * isn't a test-time concern.
 *
 * **Event-to-message translation:** the
 * orchestrator's [ChatEvent]s are wire-level
 * (per-text-delta chunks, control markers).
 * The [ChatMessage] sealed class is UI-level
 * (one row in the chat stream, sealed for the
 * Compose `when`). The mapping is:
 *
 *   ChatEvent.MiddlemanLine(s)  -> Agent(MIDDLEMAN, s)
 *   ChatEvent.WorkerLine(s)     -> Agent(WORKER, s)
 *   ChatEvent.Complete(summary) -> System(COMPLETE, summary)
 *   ChatEvent.Error(message)    -> System(ERROR, message)
 *
 * Worker tool events (e.g. a
 * `tool_execution_start` JSON in the
 * [ChatEvent.WorkerLine.line]) are passed
 * through as a regular [ChatMessage.Agent]
 * for v0.1. A future task (Phase 6.3.1 or
 * later) will parse the JSON and render
 * tool cards; for v0.1 the raw text
 * appears as a Worker bubble.
 */
class ChatViewModel(
    private val chat: ChatTransport = ChatWebSocket(baseUrl = BuildConfig.BACKEND_DEV_URL),
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _taskStatus = MutableStateFlow<ChatEvent.TaskStatus?>(null)
    val taskStatus: StateFlow<ChatEvent.TaskStatus?> = _taskStatus.asStateFlow()

    private val _debugMessages = MutableStateFlow<List<String>>(emptyList())
    val debugMessages: StateFlow<List<String>> = _debugMessages.asStateFlow()
    private val dispatchFilter = DispatchFenceFilter()

    private fun recordDebug(text: String) {
        if (text.isEmpty()) return
        // Never expose provider request payloads or credentials in the diagnostic panel.
        val safe = if (Regex("(?i)provider.{0,20}(request|dump)|request.{0,20}(body|dump)").containsMatchIn(text)) {
            "[provider request omitted]"
        } else {
            text.replace(Regex("(?i)(bearer\\s+)[^\\s\\\"',}]+"), "$1[redacted]")
                .replace(Regex("(?i)((?:api[_-]?key|authorization|token|password|secret)\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',}]+"), "$1[redacted]")
                .replace(Regex("\\bsk-[A-Za-z0-9_-]+"), "[redacted]")
        }
        _debugMessages.value = (_debugMessages.value + safe.take(512)).takeLast(100)
    }

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    init {
        // Start the WebSocket. The transport's
        // SharedFlow starts emitting once the
        // underlying socket is open; we collect
        // in viewModelScope so the coroutine is
        // cancelled if the ViewModel is cleared.
        chat.connect()
        viewModelScope.launch {
            chat.state.collect { recordDebug("connection: ${it.name}") }
        }
        viewModelScope.launch {
            chat.events.collect { event ->
                if (event is ChatEvent.TaskStatus) {
                    _taskStatus.value = event
                    recordDebug("task_status: ${event.status} (task ${event.taskId})")
                    return@collect
                }
                if (event is ChatEvent.WorkerLine) {
                    recordDebug(event.line)
                    return@collect
                }
                if (event is ChatEvent.Complete) return@collect // Middleman supplies the final reply.
                if (event is ChatEvent.Error) recordDebug("error: ${event.message}")
                val displayEvent = if (event is ChatEvent.MiddlemanLine) {
                    val result = dispatchFilter.accept(event.line)
                    recordDebug(result.debug)
                    if (result.visible.isEmpty()) return@collect
                    ChatEvent.MiddlemanLine(result.visible)
                } else event
                val message = translateEvent(displayEvent) ?: return@collect
                _messages.value = _messages.value + message
            }
        }
    }

    /**
     * Called on every keystroke in the input field.
     * No validation, no transformation — the
     * `TextField` is the source of truth for the
     * in-progress string.
     */
    fun onInputChange(newText: String) {
        _inputText.value = newText
    }

    /**
     * Send the current input text as a user
     * message.
     *
     * The trim is intentional: leading and
     * trailing whitespace from copy-paste or
     * accidental keystrokes shouldn't end up
     * in the bubble. Whitespace-only input is
     * a no-op (we don't append an empty bubble)
     * but we also don't clear the field — the
     * user might be mid-edit.
     *
     * After appending the local bubble we
     * forward the prompt to the transport.
     * `chat.send(...)` returns `false` if the
     * WS is not currently connected (e.g.
     * mid-reconnect); for v0.1 we silently
     * drop the message in that case — the
     * "Reconnecting..." pill in the chat
     * header is the user's only feedback.
     * A future task (Phase 6.3.1+) may
     * buffer the prompt and resend on
     * reconnect.
     */
    fun stopTask() {
        if (_taskStatus.value?.status == "cancel_pending") return
        if (!chat.stopTask()) {
            _messages.value = _messages.value + ChatMessage.System(
                kind = SystemEventKind.ERROR,
                summary = "Could not stop task: disconnected. Retry when connected.",
            )
        }
    }

    fun send() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        _messages.value = _messages.value + ChatMessage.User(text = text)
        _inputText.value = ""
        if (!chat.send(text)) {
            val error = "Message not sent: disconnected. Retry when connected."
            _messages.value = _messages.value + ChatMessage.System(kind = SystemEventKind.ERROR, summary = error)
            recordDebug("send failed: disconnected")
        }
    }

    /**
     * Tear down the transport. Called by the
     * Android framework when the ViewModel
     * is cleared (user navigates away, config
     * change recreates the screen, etc.).
     * `close()` cancels the connection loop
     * and the internal coroutine scope so no
     * background work outlives the ViewModel.
     */
    override fun onCleared() {
        chat.close()
        super.onCleared()
    }

    /**
     * Translate one [ChatEvent] into one
     * [ChatMessage] (or `null` for events the
     * UI doesn't surface — currently none;
     * reserved for future event kinds we want
     * to drop at the boundary).
     */
    private fun translateEvent(event: ChatEvent): ChatMessage? = when (event) {
        is ChatEvent.MiddlemanLine -> ChatMessage.Agent(
            role = AgentRole.MIDDLEMAN,
            text = event.line,
        )
        is ChatEvent.TaskStatus -> null
        is ChatEvent.WorkerLine -> ChatMessage.Agent(
            role = AgentRole.WORKER,
            text = event.line,
        )
        is ChatEvent.Complete -> ChatMessage.System(
            kind = SystemEventKind.COMPLETE,
            summary = event.summary,
        )
        is ChatEvent.Error -> ChatMessage.System(
            kind = SystemEventKind.ERROR,
            summary = event.message,
        )
    }
}
