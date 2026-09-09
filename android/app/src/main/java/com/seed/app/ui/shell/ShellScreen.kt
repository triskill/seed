package com.seed.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Create the TerminalView and wire it to the session.
        // AndroidView recreates the view on config changes, but
        // the session itself survives because it's owned by the service.
        TerminalViewConnection(
            terminalManager = terminalManager,
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
) {
    AndroidView(
        factory = { context ->
            TerminalSurface(context).apply {
                // Connect the input-owning Termux child to its session. The
                // surface renders that child's emulator after Compose lays it out.
                terminalManager.attachView(terminalView)
                requestKeyboard()
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
