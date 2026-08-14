package com.seed.app.ui.shell

import com.seed.app.data.BackendApi
import com.seed.app.data.ConfigPorts
import com.seed.app.data.ConfigRequest
import com.seed.app.data.ConfigResponse
import com.seed.app.data.HealthResponse
import com.seed.app.data.ShellExecRequest
import com.seed.app.data.ShellExecResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ShellViewModel].
 *
 * **Phase 5.5** shipped a local-only ViewModel —
 * the `submit` method appended a Command and a
 * fake `Exit(0)` to the output list.
 *
 * **Phase 6.4** wires the ViewModel to a
 * [BackendApi] (Retrofit, Phase 6.1). The tests
 * use [FakeBackendApi] — a hand-rolled stub that
 * captures outbound `shellExec` calls and
 * returns canned [ShellExecResponse]s. The fake's
 * default response is `{stdout="", stderr="",
 * exitCode=0, truncated=false}` so the original
 * Phase 5.5 assertions (output is `[Command,
 * Exit(0)]`) still hold without per-test setup.
 *
 * **Test dispatcher:** the ViewModel's
 * `viewModelScope` uses `Dispatchers.Main`. The
 * `@Before setMain(UnconfinedTestDispatcher)`
 * replaces it so the coroutine launched by
 * `submit()` runs eagerly. The fake's
 * `shellExec` is a `suspend fun` but does no
 * real I/O — it returns immediately, so the
 * coroutine completes synchronously under
 * `UnconfinedTestDispatcher` and the output
 * list is fully populated by the time
 * `submit()` returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {

    private lateinit var fakeBackend: FakeBackendApi

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeBackend = FakeBackendApi()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Phase 5.5 — local-only behavior -----------------------

    @Test
    fun initialStateIsEmptyOutputAndEmptyInput() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)
        assertEquals(emptyList<OutputLine>(), vm.output.value)
        assertEquals("", vm.input.value)
        // Phase 6.4 addition: isExecuting starts
        // false.
        assertFalse(vm.isExecuting.value)
    }

    @Test
    fun onInputChangeUpdatesInputValue() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)

        vm.onInputChange("ls -la")
        assertEquals("ls -la", vm.input.value)

        vm.onInputChange("pwd")
        assertEquals("pwd", vm.input.value)
    }

    @Test
    fun submitAppendsACommandAndAnExitLine() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("ls -la")

        vm.submit()

        val lines = vm.output.value
        assertEquals(2, lines.size)
        assertEquals(OutputLine.Command("ls -la"), lines[0])
        // Phase 5.5/6.4: the exit code is now the
        // real one from the backend (the fake
        // defaults to 0).
        assertEquals(OutputLine.Exit(code = 0), lines[1])
        // The fake's call list captured the
        // outbound call with the trimmed command.
        assertEquals(1, fakeBackend.shellExecCalls.size)
        assertEquals("ls -la", fakeBackend.shellExecCalls[0].command)
    }

    @Test
    fun submitClearsTheInput() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("ls -la")

        vm.submit()

        assertEquals("", vm.input.value)
    }

    @Test
    fun submitWithEmptyInputDoesNothing() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)

        vm.submit()

        assertTrue(vm.output.value.isEmpty())
        assertEquals(0, fakeBackend.shellExecCalls.size)
    }

    @Test
    fun submitWithWhitespaceOnlyInputDoesNotAppendButPreservesInput() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("   ")

        vm.submit()

        assertTrue(vm.output.value.isEmpty())
        // Whitespace-only isn't sent, but the
        // user's mid-edit string is preserved so
        // they can keep editing.
        assertEquals("   ", vm.input.value)
        assertEquals(0, fakeBackend.shellExecCalls.size)
    }

    @Test
    fun submitTrimsLeadingAndTrailingWhitespaceBeforeStoring() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("  ls -la  ")

        vm.submit()

        assertEquals(OutputLine.Command("ls -la"), vm.output.value[0])
    }

    @Test
    fun multipleSubmitsAppendInOrder() = runTest {
        val vm = ShellViewModel(backend = fakeBackend)

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

    // ---- Phase 6.4 — backend wiring ----------------------------

    @Test
    fun submitAppendsStdoutLineWhenResponseStdoutIsNonEmpty() = runTest {
        fakeBackend.nextResponse = ShellExecResponse(
            stdout = "hello\n",
            stderr = "",
            exitCode = 0,
            truncated = false,
        )
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("echo hello")
        vm.submit()

        val lines = vm.output.value
        assertEquals(3, lines.size)
        assertEquals(OutputLine.Command("echo hello"), lines[0])
        assertEquals(OutputLine.Stdout(text = "hello\n"), lines[1])
        assertEquals(OutputLine.Exit(code = 0), lines[2])
    }

    @Test
    fun submitAppendsStderrLineWhenResponseStderrIsNonEmpty() = runTest {
        fakeBackend.nextResponse = ShellExecResponse(
            stdout = "",
            stderr = "oops\n",
            exitCode = 2,
            truncated = false,
        )
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("false")
        vm.submit()

        val lines = vm.output.value
        assertEquals(3, lines.size)
        assertEquals(OutputLine.Command("false"), lines[0])
        assertEquals(OutputLine.Stderr(text = "oops\n"), lines[1])
        assertEquals(OutputLine.Exit(code = 2), lines[2])
    }

    @Test
    fun submitAppendsStdoutStderrAndExitWhenBothArePresent() = runTest {
        fakeBackend.nextResponse = ShellExecResponse(
            stdout = "out\n",
            stderr = "err\n",
            exitCode = 1,
            truncated = false,
        )
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("complex")
        vm.submit()

        val lines = vm.output.value
        assertEquals(4, lines.size)
        assertEquals(OutputLine.Command("complex"), lines[0])
        assertEquals(OutputLine.Stdout(text = "out\n"), lines[1])
        assertEquals(OutputLine.Stderr(text = "err\n"), lines[2])
        assertEquals(OutputLine.Exit(code = 1), lines[3])
    }

    @Test
    fun submitAppendsTruncationWarningBeforeExitWhenCaptureLimitWasReached() = runTest {
        fakeBackend.nextResponse = ShellExecResponse(
            stdout = "partial output\n",
            stderr = "",
            exitCode = 0,
            truncated = true,
        )
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("generate-lots-of-output")

        vm.submit()

        val lines = vm.output.value
        assertEquals(4, lines.size)
        assertEquals(OutputLine.Command("generate-lots-of-output"), lines[0])
        assertEquals(OutputLine.Stdout("partial output\n"), lines[1])
        assertTrue(lines[2] is OutputLine.Truncated)
        assertEquals(OutputLine.Exit(0), lines[3])
    }

    @Test
    fun submitShowsTruncationWarningEvenWhenNoOutputWasCaptured() = runTest {
        fakeBackend.nextResponse = ShellExecResponse(
            stdout = "",
            stderr = "",
            exitCode = 0,
            truncated = true,
        )
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("generate-one-huge-line")

        vm.submit()

        val lines = vm.output.value
        assertEquals(3, lines.size)
        assertEquals(OutputLine.Command("generate-one-huge-line"), lines[0])
        assertTrue(lines[1] is OutputLine.Truncated)
        assertEquals(OutputLine.Exit(0), lines[2])
    }

    @Test
    fun submitSetsIsExecutingFalseAfterResponse() = runTest {
        // The fake's shellExec is suspending but
        // returns immediately, so by the time
        // submit() returns the coroutine has
        // completed and isExecuting is back to
        // false.
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("ls")
        vm.submit()
        assertFalse(vm.isExecuting.value)
    }

    @Test
    fun submitNoOpsWhileACommandIsAlreadyInFlight() = runTest {
        // The fake's shellExec suspends on a
        // CompletableDeferred so we can hold the
        // call in flight. While it's in flight,
        // a second submit must be a no-op.
        val release = kotlinx.coroutines.CompletableDeferred<ShellExecResponse>()
        fakeBackend.shellExecHandler = { release.await() }
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("first")
        vm.submit()
        // First call is in flight; the fake
        // hasn't returned yet.
        assertTrue(vm.isExecuting.value)
        assertEquals(1, fakeBackend.shellExecCalls.size)

        // Second submit while in flight: should
        // not append a new command, not call
        // shellExec again, not clear the input.
        vm.onInputChange("second")
        vm.submit()
        assertEquals(1, fakeBackend.shellExecCalls.size)
        // The output list still has just the
        // first command line; the second
        // submit was guarded out.
        assertEquals(1, vm.output.value.size)
        assertEquals("second", vm.input.value)

        // Release the first call.
        release.complete(ShellExecResponse("", "", 0, false))
        // Give the coroutine a chance to settle.
        kotlinx.coroutines.yield()
        assertFalse(vm.isExecuting.value)
        // Now the first call has landed; the
        // output list has [Command, Exit(0)].
        assertEquals(2, vm.output.value.size)
    }

    @Test
    fun submitSurfacesBackendErrorAsExitCodeMinusOne() = runTest {
        // Network down, HTTP 5xx, deserialization
        // failure — anything that throws out of
        // shellExec. The ViewModel catches it and
        // appends an Exit(-1) so the user sees
        // something went wrong without crashing.
        fakeBackend.shellExecHandler = {
            throw java.io.IOException("connection refused")
        }
        val vm = ShellViewModel(backend = fakeBackend)
        vm.onInputChange("ls")
        vm.submit()

        val lines = vm.output.value
        assertEquals(2, lines.size)
        assertEquals(OutputLine.Command("ls"), lines[0])
        // No stdout, no stderr — just the sentinel
        // -1 exit code.
        assertEquals(OutputLine.Exit(code = -1), lines[1])
    }

    @Test
    fun cancelIsANoOpForV01() = runTest {
        // The plan calls Cancel a "best-effort
        // UI hint only" in v0.1 — the button is
        // enabled for visual feedback, but
        // tapping it doesn't actually abort
        // anything. We assert it doesn't throw,
        // doesn't change state, and doesn't
        // touch the backend.
        val vm = ShellViewModel(backend = fakeBackend)
        vm.cancel()
        assertFalse(vm.isExecuting.value)
        assertEquals(0, fakeBackend.shellExecCalls.size)
    }
}

/**
 * Hand-rolled [BackendApi] for unit tests.
 *
 * Captures every outbound `shellExec` call into
 * [shellExecCalls]. The default response (no
 * `shellExecHandler` override) is
 * `{stdout="", stderr="", exitCode=0,
 * truncated=false}` so the original Phase 5.5
 * assertions (output is `[Command, Exit(0)]`)
 * hold without per-test setup.
 *
 * The other two [BackendApi] methods
 * ([health], [putConfig]) are `TODO()` because
 * the Shell screen doesn't use them. The
 * Phase 6.3 ChatViewModel test doesn't use this
 * fake (it has its own transport), and the
 * Phase 6.5 SettingsViewModel test will likely
 * need its own fake that handles [putConfig].
 */
class FakeBackendApi : BackendApi {
    val shellExecCalls: MutableList<ShellExecRequest> = mutableListOf()
    var nextResponse: ShellExecResponse = ShellExecResponse(
        stdout = "",
        stderr = "",
        exitCode = 0,
        truncated = false,
    )

    /**
     * Optional override: if set, `shellExec` calls
     * this suspending function instead of
     * returning `nextResponse`. Used by the
     * in-flight test to hold the call open with
     * a `CompletableDeferred`.
     */
    var shellExecHandler: (suspend (ShellExecRequest) -> ShellExecResponse)? = null

    override suspend fun health(): HealthResponse =
        TODO("Shell screen doesn't use /health")

    override suspend fun shellExec(request: ShellExecRequest): ShellExecResponse {
        shellExecCalls.add(request)
        return shellExecHandler?.invoke(request) ?: nextResponse
    }

    override suspend fun putConfig(payload: ConfigRequest): ConfigResponse =
        TODO("Shell screen doesn't use /config")
}
