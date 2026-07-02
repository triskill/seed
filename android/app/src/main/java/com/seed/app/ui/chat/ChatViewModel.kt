package com.seed.app.ui.chat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the Chat tab.
 *
 * **Phase 5.4** ships a local-only ViewModel: it
 * appends user-typed messages to a `StateFlow` and
 * clears the input field. There's no backend
 * connection yet — the user can type, see their
 * own message appear, and verify the wiring works.
 *
 * **Phase 6.3** (Task 6.3, "Wire Chat screen") will
 * add a `ChatWebSocket` dependency: on `send()`, the
 * ViewModel will also push the prompt over the
 * WebSocket; the WebSocket's `Flow<ChatEvent>` will
 * be folded into the messages `StateFlow` so the
 * agent's text output appears as the user watches.
 * The shape of the public API
 * ([messages], [inputText], [onInputChange], [send])
 * stays the same — Phase 6.3 only adds work inside
 * the existing methods, not new methods.
 *
 * **Why two flows, not one:** the input row needs
 * to update on every keystroke, and the message
 * list updates only on `send()`. Splitting them
 * means typing doesn't recompose the `LazyColumn`.
 */
class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

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
     * Send the current input text as a user message.
     *
     * The trim is intentional: leading and trailing
     * whitespace from copy-paste or accidental
     * keystrokes shouldn't end up in the bubble.
     * Whitespace-only input is a no-op (we don't
     * append an empty bubble) but we also don't
     * clear the field — the user might be mid-edit.
     */
    fun send() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        _messages.value = _messages.value + ChatMessage.User(text = text)
        _inputText.value = ""
    }
}
