package com.seed.app.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seed.app.BuildConfig
import com.seed.app.data.ApiModule
import com.seed.app.data.BackendApi
import com.seed.app.data.ShellExecRequest
import com.seed.app.data.ShellExecResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Shell tab.
 *
 * **Phase 5.5** shipped a local-only ViewModel —
 * it appended a [OutputLine.Command] and a fake
 * [OutputLine.Exit] to the output list, then
 * cleared the input.
 *
 * **Phase 6.4** wires the ViewModel to the
 * backend via the [BackendApi] Retrofit client
 * (introduced in Phase 6.1):
 *   - the constructor now takes a [BackendApi]
 *     (default = [ApiModule.default], bound to
 *     [BuildConfig.BACKEND_DEV_URL]);
 *   - [submit] now launches a coroutine in
 *     [viewModelScope] that calls
 *     `POST /shell/exec`, then appends the
 *     response as a [OutputLine.Stdout] line, an
 *     optional [OutputLine.Stderr] line (only if
 *     the response has stderr), an optional
 *     [OutputLine.Truncated] warning when the
 *     backend capture limit was reached, and a
 *     [OutputLine.Exit] line with the real
 *     exit code from the backend;
 *   - a new [isExecuting] [StateFlow] is `true`
 *     while a `POST /shell/exec` is in flight
 *     and `false` otherwise. The Shell screen's
 *     Cancel button (permanently disabled in
 *     Phase 5.5) is now driven by this flow;
 *   - a new [cancel] method is a no-op for v0.1
 *     (the backend's `POST /shell/exec` has no
 *     cancel endpoint — that's a separate Phase
 *     10 task). The button is enabled for
 *     "best-effort" UI feedback, but tapping
 *     it doesn't actually abort the HTTP call;
 *     the call runs to completion and the
 *     response is appended as normal.
 *
 * The original public API ([output], [input],
 * [onInputChange], [submit]) keeps the same
 * shape as Phase 5.5 — the Compose screen
 * doesn't need to change in a breaking way.
 * The screen picks up [isExecuting] to drive
 * the Cancel button's enabled state.
 *
 * **Submit is now fire-and-forget.** The user
 * can keep typing in the input field while a
 * command runs; the API call happens in the
 * background. Multiple submits in a row are
 * guarded by [isExecuting] — the second
 * submit no-ops while the first is in flight.
 * A future task may add a queue so multiple
 * commands can run back-to-back.
 */
class ShellViewModel(
    private val backend: BackendApi = ApiModule.default,
) : ViewModel() {

    private val _output = MutableStateFlow<List<OutputLine>>(emptyList())
    val output: StateFlow<List<OutputLine>> = _output.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /**
     * `true` while a `POST /shell/exec` call is
     * in flight. The Shell screen uses this to
     * drive the Cancel button's enabled state
     * (Phase 6.4 wires the button; the cancel
     * action itself is a v0.1 no-op).
     */
    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    /**
     * Called on every keystroke in the input field.
     * No validation, no transformation — the
     * `TextField` is the source of truth for the
     * in-progress string.
     */
    fun onInputChange(newText: String) {
        _input.value = newText
    }

    /**
     * Submit the current input as a shell command.
     *
     * The trim is intentional: leading and
     * trailing whitespace from copy-paste or
     * accidental keystrokes shouldn't end up in
     * the log. The echoed command is the *trimmed*
     * text, not the raw input, so the user sees
     * exactly what was sent to the backend.
     *
     * Whitespace-only input is a no-op (we don't
     * append an empty command + exit code, and
     * we don't clear the field — the user might
     * be mid-edit).
     *
     * **Phase 6.4** behavior: while a call is in
     * flight, additional submits are no-ops
     * (guarded by [isExecuting]). The first
     * submit flips [isExecuting] to `true`,
     * launches the call, and flips it back to
     * `false` once the response lands. The
     * response is appended as a [OutputLine.Stdout]
     * line (if stdout is non-empty), a
     * [OutputLine.Stderr] line (if stderr is
     * non-empty), an [OutputLine.Truncated]
     * warning when the response says output was
     * truncated, and an [OutputLine.Exit] line
     * with the real exit code. Failures
     * (network down, backend 500) surface as a
     * special [OutputLine.Exit] with code `-1`
     * so the user can see something went wrong
     * without crashing the ViewModel.
     */
    fun submit() {
        val command = _input.value.trim()
        if (command.isEmpty()) return
        if (_isExecuting.value) return
        // Append the command line eagerly so
        // the user sees their typing reflected
        // in the log even if the backend takes
        // a moment to respond.
        _output.value = _output.value + OutputLine.Command(text = command)
        _input.value = ""
        _isExecuting.value = true
        viewModelScope.launch {
            val response: ShellExecResponse? = try {
                backend.shellExec(ShellExecRequest(command = command))
            } catch (e: Exception) {
                // Network error, HTTP 4xx/5xx, etc.
                // We surface this as a -1 exit
                // code (no real POSIX exit code
                // is -1; the user will see "exit
                // -1" and know it was a backend
                // failure, not a real shell exit).
                null
            }
            appendResponse(response)
            _isExecuting.value = false
        }
    }

    /**
     * Append the response of `POST /shell/exec`
     * to the output list.
     *
     * The output protocol is:
     *   - [OutputLine.Stdout] iff `response.stdout`
     *     is non-empty (we don't render an empty
     *     stdout line; the [OutputLine.Exit] line
     *     below is the visual "command finished"
     *     signal);
     *   - [OutputLine.Stderr] iff `response.stderr`
     *     is non-empty (same rule);
     *   - [OutputLine.Truncated] iff the backend's
     *     capture cap was reached;
     *   - [OutputLine.Exit] always, with the
     *     response's exit code (or `-1` for
     *     network/HTTP failure, see [submit]).
     *
     * We append in submission-order, so a
     * command with both stdout and stderr
     * renders as: [Stdout, Stderr, Truncated, Exit].
     */
    private fun appendResponse(response: ShellExecResponse?) {
        val current = _output.value
        val next = if (response == null) {
            current + OutputLine.Exit(code = -1)
        } else {
            current + buildList {
                if (response.stdout.isNotEmpty()) {
                    add(OutputLine.Stdout(text = response.stdout))
                }
                if (response.stderr.isNotEmpty()) {
                    add(OutputLine.Stderr(text = response.stderr))
                }
                if (response.truncated) {
                    add(OutputLine.Truncated())
                }
                add(OutputLine.Exit(code = response.exitCode))
            }
        }
        _output.value = next
    }

    /**
     * Best-effort cancel of the in-flight command.
     *
     * **Phase 6.4:** this is a no-op. The
     * backend's `POST /shell/exec` has no cancel
     * endpoint (the orchestrator's
     * `session.exec()` runs the command to
     * completion inside a PTY and the HTTP
     * response is the full result). Tapping
     * Cancel flips [isExecuting] to `false` so
     * the UI stops showing the "running"
     * affordance, but the HTTP call continues
     * in the background and the response will
     * still land in the output list. A future
     * task (Phase 10) may add a real cancel
     * (e.g. an `abort` event channel or a
     * `/shell/cancel` endpoint that sends
     * SIGTERM to the PTY's process group) and
     * this method can be wired to it.
     */
    fun cancel() {
        // No-op for v0.1. The button is enabled
        // for "UI hint only" feedback — tapping
        // it doesn't actually abort anything.
        // See the kdoc for the full rationale.
    }
}
