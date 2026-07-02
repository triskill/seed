package com.seed.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ShellViewModel].
 *
 * Phase 5.5 ships a local-only ViewModel — no
 * backend, no `POST /shell/exec`. The public API
 * (output / input / onInputChange / submit) is the
 * same shape Phase 6.4 will need for the real
 * wiring, so this test file is stable across both
 * phases; Phase 6.4 only adds tests that exercise
 * the new behaviour (real network call, real
 * stdout/stderr appending, cancel-during-flight).
 *
 * The chat screen has the same single-ViewModel
 * pattern with a no-op backend, and the
 * `ChatViewModelTest` suite is the structural
 * template for this one (JUnit-only, no coroutines
 * plumbing since the ViewModel doesn't launch
 * anything yet).
 */
class ShellViewModelTest {

    @Test
    fun initialStateIsEmptyOutputAndEmptyInput() {
        val vm = ShellViewModel()

        assertEquals(emptyList<OutputLine>(), vm.output.value)
        assertEquals("", vm.input.value)
    }

    @Test
    fun onInputChangeUpdatesInputValue() {
        val vm = ShellViewModel()

        vm.onInputChange("ls -la")
        assertEquals("ls -la", vm.input.value)

        vm.onInputChange("pwd")
        assertEquals("pwd", vm.input.value)
    }

    @Test
    fun submitAppendsACommandAndAnExitLine() {
        val vm = ShellViewModel()
        vm.onInputChange("ls -la")

        vm.submit()

        val lines = vm.output.value
        assertEquals(2, lines.size)
        assertEquals(OutputLine.Command("ls -la"), lines[0])
        // Phase 5.5 is local-only — the fake
        // "execution" always reports exit code 0.
        // Phase 6.4 will replace this with the real
        // exit code from the backend.
        assertEquals(OutputLine.Exit(code = 0), lines[1])
    }

    @Test
    fun submitClearsTheInput() {
        val vm = ShellViewModel()
        vm.onInputChange("ls -la")

        vm.submit()

        assertEquals("", vm.input.value)
    }

    @Test
    fun submitWithEmptyInputDoesNothing() {
        val vm = ShellViewModel()

        vm.submit()

        assertTrue(vm.output.value.isEmpty())
    }

    @Test
    fun submitWithWhitespaceOnlyInputDoesNotAppendButPreservesInput() {
        val vm = ShellViewModel()
        vm.onInputChange("   ")

        vm.submit()

        assertTrue(vm.output.value.isEmpty())
        // Whitespace-only isn't sent, but the
        // user's mid-edit string is preserved so
        // they can keep editing (e.g. just hit
        // space to test the layout, then go back).
        assertEquals("   ", vm.input.value)
    }

    @Test
    fun submitTrimsLeadingAndTrailingWhitespaceBeforeStoring() {
        val vm = ShellViewModel()
        vm.onInputChange("  ls -la  ")

        vm.submit()

        assertEquals(OutputLine.Command("ls -la"), vm.output.value[0])
    }

    @Test
    fun multipleSubmitsAppendInOrder() {
        val vm = ShellViewModel()

        vm.onInputChange("pwd")
        vm.submit()
        vm.onInputChange("whoami")
        vm.submit()
        vm.onInputChange("date")
        vm.submit()

        val lines = vm.output.value
        // Three commands, three exits = six lines.
        assertEquals(6, lines.size)
        assertEquals(OutputLine.Command("pwd"), lines[0])
        assertEquals(OutputLine.Exit(0), lines[1])
        assertEquals(OutputLine.Command("whoami"), lines[2])
        assertEquals(OutputLine.Exit(0), lines[3])
        assertEquals(OutputLine.Command("date"), lines[4])
        assertEquals(OutputLine.Exit(0), lines[5])
    }
}
