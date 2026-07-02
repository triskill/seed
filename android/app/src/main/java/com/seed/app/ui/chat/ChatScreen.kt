package com.seed.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Chat tab — text conversation with the Seed orchestrator.
 *
 * Phase 5.4 replaces the 5.2 placeholder with:
 *   - a `LazyColumn` of [MessageBubble] rows
 *     ([ChatViewModel.messages] is the source of truth);
 *   - a bottom input bar (text field + send button);
 *   - auto-scroll to the bottom whenever a new
 *     message arrives (so the user always sees their
 *     own message and the latest agent output);
 *   - `imePadding()` so the input bar is pushed up
 *     above the soft keyboard.
 *
 * **No backend wiring yet.** `ChatViewModel.send()`
 * just appends a [ChatMessage.User] to the in-memory
 * list. The WebSocket client lands in Phase 6.2
 * (Task 6.2, `ChatWebSocket`); the screen wiring
 * lands in Phase 6.3 (Task 6.3, "Wire Chat screen").
 * Both phases will only *add* behaviour to the
 * existing ViewModel methods — the public API
 * ([ChatViewModel.messages], [ChatViewModel.inputText],
 * [ChatViewModel.onInputChange], [ChatViewModel.send])
 * stays the same.
 *
 * **ViewModel scoping:** `viewModel()` defaults to
 * the closest [androidx.lifecycle.ViewModelStoreOwner],
 * which inside a `NavHost` is the current
 * `NavBackStackEntry`. So each tab gets its own
 * ChatViewModel instance, and navigating away
 * (with `saveState = true` in `SeedNav`) preserves
 * the in-memory message list across tab switches.
 *
 * The [modifier] parameter is applied to the outer
 * `Column`. The `NavHost` in `SeedNav` already
 * applies the Scaffold's `innerPadding` to keep
 * the chat content above the bottom navigation
 * bar, so we don't double-pad here.
 */
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom whenever the list
    // grows. Keyed on `messages.size` so this only
    // fires when a new row is appended (not on
    // every recomposition triggered by the input
    // field). `scrollToItem` is a no-op when the
    // list is empty, so the early return isn't
    // needed — but skipping the call is cheaper
    // than re-laying out the list.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { testTag = "chat-message-list" },
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                items = messages,
                key = { it.id },
            ) { message ->
                MessageBubble(message = message)
            }
        }

        ChatInputBar(
            value = inputText,
            onValueChange = viewModel::onInputChange,
            onSend = viewModel::send,
        )
    }
}

/**
 * The bottom input row: a text field and a send
 * button. Lives in a `Surface` with a slight
 * elevation so it visually separates from the
 * message list.
 */
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics { testTag = "chat-input" },
                placeholder = { Text("Type a message") },
                singleLine = true,
                // IME "Send" action triggers send too,
                // so the user can submit without
                // tapping the button (faster on
                // mobile).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                // Disabled when the input is empty or
                // whitespace-only so the user can't
                // send a blank bubble. We mirror the
                // `send()` policy here.
                enabled = value.isNotBlank(),
                modifier = Modifier.semantics { testTag = "chat-send" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Chat tab empty")
@Composable
private fun ChatScreenEmptyPreview() {
    MaterialTheme {
        ChatScreen()
    }
}
