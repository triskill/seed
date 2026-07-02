package com.seed.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatViewModel] state transitions.
 *
 * The ViewModel is plain Kotlin (no Android imports
 * other than `ViewModel` from the lifecycle
 * artifact, which is JVM-testable via the same
 * dependency tree the app uses at runtime).
 *
 * Phase 5.4 ships the ViewModel in a "local-only"
 * configuration: it accepts text and appends user
 * messages, but doesn't talk to the backend yet.
 * Phase 6.3 (Task 6.3) will layer the WebSocket
 * client on top — the same test file will gain
 * integration tests with a fake `ChatWebSocket` at
 * that point.
 */
class ChatViewModelTest {

    @Test
    fun `initial state is empty messages and empty input`() {
        val vm = ChatViewModel()
        assertEquals(emptyList<ChatMessage>(), vm.messages.value)
        assertEquals("", vm.inputText.value)
    }

    @Test
    fun `onInputChange updates inputText`() {
        val vm = ChatViewModel()
        vm.onInputChange("hello")
        assertEquals("hello", vm.inputText.value)
    }

    @Test
    fun `send appends a user message`() {
        val vm = ChatViewModel()
        vm.onInputChange("hello")
        vm.send()

        val messages = vm.messages.value
        assertEquals(1, messages.size)
        val msg = messages[0]
        assertTrue("expected User, got $msg", msg is ChatMessage.User)
        assertEquals("hello", (msg as ChatMessage.User).text)
    }

    @Test
    fun `send clears the input after sending`() {
        val vm = ChatViewModel()
        vm.onInputChange("hello")
        vm.send()
        assertEquals("", vm.inputText.value)
    }

    @Test
    fun `send with empty input does nothing`() {
        val vm = ChatViewModel()
        vm.send()
        assertEquals(emptyList<ChatMessage>(), vm.messages.value)
        // Input is still empty (no change to verify,
        // but no exception either).
        assertEquals("", vm.inputText.value)
    }

    @Test
    fun `send with whitespace-only input does not append but preserves input`() {
        // Whitespace-only input is invalid (nothing
        // meaningful to send) but the user might be
        // mid-typing; we should not yank their text.
        val vm = ChatViewModel()
        vm.onInputChange("   \n\t  ")
        vm.send()
        assertEquals(emptyList<ChatMessage>(), vm.messages.value)
        assertEquals("   \n\t  ", vm.inputText.value)
    }

    @Test
    fun `send appends multiple messages in order`() {
        val vm = ChatViewModel()
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
    fun `send trims leading and trailing whitespace before storing`() {
        val vm = ChatViewModel()
        vm.onInputChange("  hello world  ")
        vm.send()

        val messages = vm.messages.value
        assertEquals(1, messages.size)
        // Trimmed so the bubble doesn't have phantom
        // leading/trailing spaces.
        assertEquals("hello world", (messages[0] as ChatMessage.User).text)
    }
}
