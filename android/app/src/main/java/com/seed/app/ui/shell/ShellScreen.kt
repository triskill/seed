package com.seed.app.ui.shell

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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Shell tab — interactive shell into the Seed
 * runtime.
 *
 * Phase 5.5 replaces the 5.2 placeholder with:
 *   - a top input row (text field + Run button +
 *     Cancel button);
 *   - a `LazyColumn` of [OutputLineRow] rows below
 *     ([ShellViewModel.output] is the source of
 *     truth);
 *   - auto-scroll to the bottom whenever a new line
 *     is appended;
 *   - `imePadding()` so the input bar is pushed up
 *     above the soft keyboard.
 *
 * **Layout note** — the input is at the *top* and
 * the output is at the *bottom*. This is the
 * opposite of a desktop terminal (where the prompt
 * is at the bottom and history scrolls up above
 * it), but it matches a CI-log / "form + log"
 * pattern: the input is always visible, easy to
 * reach with one hand, and the latest output
 * appears at the bottom of the scrollable area,
 * right above the keyboard.
 *
 * **No backend wiring yet.** `ShellViewModel.submit()`
 * just appends a `Command` and a fake `Exit(0)` to
 * the in-memory list. The `ShellApi` (Retrofit
 * client for `POST /shell/exec`) lands in Task 6.1
 * and the screen wiring lands in Task 6.4
 * ("Wire Shell screen"). Both phases will only
 * *add* behaviour to the existing ViewModel
 * methods — the public API
 * ([ShellViewModel.output], [ShellViewModel.input],
 * [ShellViewModel.onInputChange],
 * [ShellViewModel.submit]) stays the same.
 *
 * **Cancel button** is rendered (so the layout is
 * stable when Phase 6.4 enables it) but is
 * permanently `enabled = false` in 5.5 — there
 * is no "command running" state. Phase 6.4 will
 * add a `cancel()` method on the ViewModel and
 * an `isExecuting` flow that drives the button's
 * `enabled` flag.
 */
@Composable
fun ShellScreen(
    modifier: Modifier = Modifier,
    viewModel: ShellViewModel = viewModel(),
) {
    val output by viewModel.output.collectAsState()
    val input by viewModel.input.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom whenever the list
    // grows. Keyed on `output.size` so this only
    // fires when a new line is appended (not on
    // every recomposition triggered by the input
    // field).
    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            listState.animateScrollToItem(output.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ShellInputBar(
            value = input,
            onValueChange = viewModel::onInputChange,
            onRun = viewModel::submit,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { testTag = "shell-output-list" },
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                items = output,
                key = { it.id },
            ) { line ->
                OutputLineRow(line = line)
            }
        }
    }
}

/**
 * The top input row: a monospaced text field, a
 * Run (send) button, and a Cancel button. The
 * field and the buttons sit in a `Surface` with
 * a slight elevation so it visually separates
 * from the output area below.
 */
@Composable
private fun ShellInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onRun: () -> Unit,
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
                    .semantics { testTag = "shell-input" },
                placeholder = { Text("Type a command") },
                singleLine = true,
                // IME "Send" action triggers run
                // too, so the user can submit
                // without tapping the button.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onRun() }),
                // The text in a shell is monospaced
                // so the prompt and the command line
                // up visually. (We're not rendering
                // the prompt inside the field — the
                // `$ ` lives in the output row —
                // but a monospaced field matches
                // the monospaced output below.)
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = onRun,
                // Disabled when the input is empty
                // or whitespace-only so the user
                // can't submit a blank command. We
                // mirror the `submit()` policy
                // here.
                enabled = value.isNotBlank(),
                modifier = Modifier.semantics { testTag = "shell-run" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Run",
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            // Permanently disabled in 5.5 — see
            // [ShellScreen] kdoc.
            IconButton(
                onClick = { /* Phase 6.4: viewModel.cancel() */ },
                enabled = false,
                modifier = Modifier.semantics { testTag = "shell-cancel" },
            ) {
                Icon(
                    imageVector = Icons.Filled.Block,
                    // Hidden from a11y / TalkBack
                    // while the button is disabled.
                    contentDescription = "Cancel running command",
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Shell tab empty")
@Composable
private fun ShellScreenEmptyPreview() {
    MaterialTheme {
        ShellScreen()
    }
}
