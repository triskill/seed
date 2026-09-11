package com.seed.app.ui.shell

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.viewinterop.AndroidView
import com.seed.app.runtime.SeedTerminalManager

/**
 * Shell tab — an interactive terminal session powered by Termux's
 * terminal-view and terminal-emulator libraries, running a secondary
 * PRoot instance (`/bin/sh -l`) inside the shared Alpine rootfs.
 *
 * The layout is simple: a full-screen [TerminalView] inside an
 * [AndroidView] wrapper. No input bar, no scrollable log — the
 * terminal emulator itself handles display, input routing, and
 * PTY I/O.
 *
 * [attachView] / [detachView] lifecycle:
 * - When the AndroidView is first inflated, [SeedTerminalManager.attachView]
 *   connects the [TerminalView] to the session. This is where the PTY
 *   subprocess actually starts (Termux waits for the view to have a
 *   size before creating the subprocess).
 * - When the view is about to be destroyed (navigation away, activity
 *   destroy), [detachView] releases the UI connection. The shell
 *   process continues running in the background.
 * - The [TerminalSession] is owned by [SeedTerminalManager], which is
 *   owned by [RuntimeService], so it outlives activity destruction
 *   and nav graph re-creation. Only [SeedTerminalManager.close()]
 *   terminates the session.
 *
 * Architecture note:
 * The old ShellScreen used ShellViewModel + BackedApi.shellExec() to
 * run commands via POST /shell/exec and display responses in a LazyColumn.
 * This composable replaces that entirely — the terminal emulator handles
 * both input and output directly through the PTY, with ANSI color support
 * and proper terminal semantics (resize, scrollback, etc.).
 */
@Composable
fun ShellScreen(
    terminalManager: SeedTerminalManager,
    modifier: Modifier = Modifier,
) {
    // The terminal is an Android View, so keep its reference only while this
    // composable owns it. The manager retains the session, not the activity view.
    val terminalSurface = remember(terminalManager) { mutableStateOf<TerminalSurface?>(null) }
    val controlKeyActive by terminalManager.controlKeyActive.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            // enableEdgeToEdge() leaves IME handling to Compose. Applying this
            // to the whole column reduces AndroidView's measured height whenever
            // the software keyboard is visible, which makes TerminalView update
            // its rows instead of rendering underneath the keyboard.
            .imePadding(),
    ) {
        TerminalViewConnection(
            terminalManager = terminalManager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            onSurfaceAvailable = { terminalSurface.value = it },
            onSurfaceReleased = { released ->
                if (terminalSurface.value === released) {
                    terminalSurface.value = null
                }
            },
        )
        TerminalExtraKeys(
            controlKeyActive = controlKeyActive,
            onControlClick = {
                terminalManager.toggleControlKey()
                terminalSurface.value?.requestKeyboard()
            },
            onKeyClick = { keyCode ->
                terminalSurface.value?.sendKey(keyCode)
            },
        )
    }
}

/**
 * A compact, always-visible subset of Termux's extra keys. Arrow and control
 * sequences are awkward or absent on many software keyboards; routing them
 * through [TerminalView.handleKeyCode] preserves the emulator's current cursor
 * application mode.
 */
@Composable
private fun TerminalExtraKeys(
    controlKeyActive: Boolean,
    onControlClick: () -> Unit,
    onKeyClick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics { contentDescription = "Terminal extra keys" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TerminalExtraKey("ESC", "Escape") { onKeyClick(KeyEvent.KEYCODE_ESCAPE) }
        TerminalExtraKey("TAB", "Tab") { onKeyClick(KeyEvent.KEYCODE_TAB) }
        TerminalExtraKey(
            label = "CTRL",
            description = if (controlKeyActive) "Control enabled" else "Control",
            active = controlKeyActive,
            onClick = onControlClick,
        )
        TerminalExtraKey("←", "Left arrow") { onKeyClick(KeyEvent.KEYCODE_DPAD_LEFT) }
        TerminalExtraKey("↓", "Down arrow") { onKeyClick(KeyEvent.KEYCODE_DPAD_DOWN) }
        TerminalExtraKey("↑", "Up arrow") { onKeyClick(KeyEvent.KEYCODE_DPAD_UP) }
        TerminalExtraKey("→", "Right arrow") { onKeyClick(KeyEvent.KEYCODE_DPAD_RIGHT) }
    }
}

@Composable
private fun RowScope.TerminalExtraKey(
    label: String,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) colorScheme.primaryContainer else colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Creates a [TerminalView] inside an [AndroidView] and connects it
 * to the terminal session.
 *
 * **setTextSize is required.** Termux's [TerminalView] keeps its
 * renderer null until [setTextSize] is called. If the view goes
 * through [onSizeChanged] layout without a renderer,
 * [TerminalView.updateSize] NPEs. Calling [setTextSize] before the
 * initial layout guarantees a valid renderer.
 *
 * **One-time setup in factory.** The factory lambda is the correct
 * place for one-time setup (setTextSize, focus flags, attach). The
 * trailing update lambda of [AndroidView] is NOT called on creation
 * — it is called on every recomposition, so attaching the view
 * inside it would re-attach on every update. All setup belongs in
 * [factory].
 */
@Composable
private fun TerminalViewConnection(
    terminalManager: SeedTerminalManager,
    modifier: Modifier,
    onSurfaceAvailable: (TerminalSurface) -> Unit,
    onSurfaceReleased: (TerminalSurface) -> Unit,
) {
    AndroidView(
        factory = { context ->
            TerminalSurface(context).apply {
                // Connect the input-owning Termux child to its session. The
                // surface renders that child's emulator after Compose lays it out.
                terminalManager.attachView(terminalView)
                onSurfaceAvailable(this)
                requestKeyboard()
            }
        },
        modifier = modifier,
        // AndroidView invokes this exactly once when the composable leaves
        // composition. Keep the service-owned shell alive, but release this
        // activity view and its input/client connection.
        onRelease = { surface ->
            terminalManager.detachView(surface.terminalView)
            onSurfaceReleased(surface)
        },
    )
}
