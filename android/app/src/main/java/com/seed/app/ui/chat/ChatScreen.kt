package com.seed.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seed.app.R

/**
 * Chat tab — text conversation with the Seed orchestrator.
 *
 * Phase 5.2 ships a placeholder. Phase 5.4 (Chat UI)
 * replaces it with a `MessageBubble` list, an input row,
 * and a `ChatViewModel` that drives a fake message stream
 * (no backend wiring yet — that lands in Phase 6.2 with
 * the WebSocket client).
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_screen_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.chat_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true, name = "Chat tab placeholder")
@Composable
private fun ChatScreenPreview() {
    MaterialTheme {
        ChatScreen()
    }
}
