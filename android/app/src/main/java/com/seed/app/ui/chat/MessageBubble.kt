package com.seed.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Renders one [ChatMessage] as a single row in the
 * chat stream.
 *
 * The three [ChatMessage] subclasses map to three
 * visual variants:
 *
 *   - [ChatMessage.User]  → right-aligned bubble,
 *                           primaryContainer fill.
 *   - [ChatMessage.Agent] → left-aligned card with
 *                           a role label, surfaceVariant
 *                           fill.
 *   - [ChatMessage.System]→ full-width banner with
 *                           colour keyed to the
 *                           [SystemEventKind] (success
 *                           vs. reload vs. error).
 *
 * The three variants are `private` Composables in
 * this file because they're not interesting on their
 * own — the entry point is the sealed-class `when`
 * in [MessageBubble] which makes the exhaustiveness
 * check verify all three cases are handled.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    when (message) {
        is ChatMessage.User ->
            UserMessageBubble(text = message.text, modifier = modifier)
        is ChatMessage.Agent ->
            AgentMessageCard(
                role = message.role,
                text = message.text,
                modifier = modifier,
            )
        is ChatMessage.System ->
            SystemMessageBanner(
                kind = message.kind,
                summary = message.summary,
                modifier = modifier,
            )
    }
}

@Composable
private fun UserMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    // Outer Row aligns the bubble to the end
    // (right in LTR, left in RTL — which matches
    // user expectations for "my own" messages).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 4.dp,
            ),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AgentMessageCard(
    role: AgentRole,
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        // Agent cards span the full width with a
        // generous left margin so they read as
        // "theirs" (vs. the right-aligned user
        // bubble). The text wraps inside the card.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                text = role.displayName,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SystemMessageBanner(
    kind: SystemEventKind,
    summary: String?,
    modifier: Modifier = Modifier,
) {
    // Different `SystemEventKind`s get different
    // container colours so the user can scan the
    // stream and pick out errors / completions at
    // a glance. Tones come from the M3 colour
    // scheme — no hard-coded colour values.
    val (label, containerColor, contentColor) = when (kind) {
        SystemEventKind.COMPLETE -> Triple(
            "Task complete",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        SystemEventKind.APP_RELOAD -> Triple(
            "App reloading",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SystemEventKind.ERROR -> Triple(
            "Error",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
            if (summary != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
