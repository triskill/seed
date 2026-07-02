package com.seed.app.ui.shell

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the Shell tab.
 *
 * **Phase 5.5** ships a local-only ViewModel: it
 * appends a [OutputLine.Command] and a
 * [OutputLine.Exit] to the output list, then
 * clears the input. There's no `POST /shell/exec`
 * yet — the user can type, see their command
 * echoed, see a fake "exit 0" line, and verify
 * the wiring works.
 *
 * **Phase 6.4** (Task 6.4, "Wire Shell screen")
 * will add a `ShellApi` dependency (Retrofit, in
 * Task 6.1) and use coroutines: on `submit()`, the
 * ViewModel will launch in `viewModelScope`, call
 * `POST /shell/exec`, and append the response's
 * stdout / stderr / exit-code lines as they come
 * back. The shape of the public API ([output],
 * [input], [onInputChange], [submit]) stays the
 * same — Phase 6.4 only adds work inside the
 * existing methods, not new methods.
 *
 * **Why two flows, not one:** the input row needs
 * to update on every keystroke, and the output
 * list updates only on `submit()`. Splitting them
 * means typing doesn't recompose the `LazyColumn`.
 */
class ShellViewModel : ViewModel() {

    private val _output = MutableStateFlow<List<OutputLine>>(emptyList())
    val output: StateFlow<List<OutputLine>> = _output.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

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
     * The trim is intentional: leading and trailing
     * whitespace from copy-paste or accidental
     * keystrokes shouldn't end up in the log. The
     * echoed command is the *trimmed* text, not the
     * raw input, so the user sees exactly what was
     * sent.
     *
     * Whitespace-only input is a no-op (we don't
     * append an empty command + fake exit), but we
     * also don't clear the field — the user might
     * be mid-edit.
     *
     * **Phase 5.5** always reports `Exit(0)` —
     * there's no actual command execution. Phase
     * 6.4 will replace this with the real exit
     * code from `POST /shell/exec`.
     */
    fun submit() {
        val command = _input.value.trim()
        if (command.isEmpty()) return
        _output.value = _output.value + listOf(
            OutputLine.Command(text = command),
            // Phase 6.4: replace this with the real
            // exit code and append stdout/stderr
            // lines from the API response.
            OutputLine.Exit(code = 0),
        )
        _input.value = ""
    }
}
