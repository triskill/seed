package com.seed.app.ui.shell

import java.util.UUID

/**
 * One row in the shell's output stream.
 *
 * Five cases — the same three a real terminal
 * surfaces (echoed command, stdout, stderr) plus
 * status rows for truncation and the exit code:
 *
 *   - [Command] — the line the user submitted,
 *                 rendered with a `$ ` prompt
 *                 prefix and a primary-colour
 *                 tint so the user can see what
 *                 they ran.
 *   - [Stdout]  — a normal output line.
 *   - [Stderr]  — a diagnostic line, rendered in
 *                 the error colour.
 *   - [Truncated] — a warning that the backend
 *                   stopped capturing after its
 *                   output limit was reached.
 *   - [Exit]      — a status line reporting the
 *                   command's exit code. Renders as
 *                   `[exit 0]` / `[exit 1]` etc. in
 *                   a muted colour.
 *
 * Sealed because [com.seed.app.ui.shell.OutputLineRow]
 * uses Compose `when` over this class and the
 * compiler enforces every case is handled —
 * adding a new variant (e.g. `System` for shell
 * banners) will be a compile error in the row
 * until handled.
 *
 * Each subclass assigns its own [id] (random UUID)
 * so `LazyColumn` `key` can preserve item identity
 * across list edits (insert / delete / reorder).
 */
sealed class OutputLine {
    abstract val id: String

    /** Echo of the user-submitted command. */
    data class Command(val text: String) : OutputLine() {
        override val id: String = Companion.newId()
    }

    /** A line of stdout from the command. */
    data class Stdout(val text: String) : OutputLine() {
        override val id: String = Companion.newId()
    }

    /** A line of stderr from the command. */
    data class Stderr(val text: String) : OutputLine() {
        override val id: String = Companion.newId()
    }

    /** The backend reached its output capture limit. */
    class Truncated : OutputLine() {
        override val id: String = Companion.newId()
    }

    /** The command's exit status. */
    data class Exit(val code: Int) : OutputLine() {
        override val id: String = Companion.newId()
    }

    /**
     * Helpers for default-parameter expressions on
     * the data classes. Same workaround as
     * `ChatMessage`: Kotlin doesn't resolve
     * private members of an enclosing class in a
     * nested data class's default-parameter
     * expression.
     */
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
