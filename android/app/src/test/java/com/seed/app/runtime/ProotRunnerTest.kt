package com.seed.app.runtime

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream

/**
 * Unit tests for [ProotRunner].
 *
 * The tests do not spawn a real `proot` process — they inject a
 * [ProcessFactory] that captures the command and returns a
 * [FakeProcess] whose stdout/stderr/exit behaviour is fully
 * controlled by the test. This keeps the suite JVM-only (no
 * arm64 host, no emulator, no real proot binary) and fast.
 *
 * The trade-off: the test does not exercise the *real* proot
 * command, only the command *construction* + the stream-draining
 * coroutine. The on-device integration test in
 * `docs/plans/2026-07-03-embedded-runtime.md` §6 covers the
 * real proot spawn and the real uvicorn boot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProotRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun startSpawnsProotWithRootfsAndUvicornCommand() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot").apply { setExecutable(true, false) }
        val fake = RecordingProcessFactory(FakeProcess(stdout = "", stderr = ""))

        ProotRunner(prootExecutable = proot, rootfsDir = rootfs, factory = fake)
            .start(this)

        assertEquals(
            listOf(
                proot.absolutePath,
                "-r", rootfs.absolutePath,
                // Bind /dev and /proc so PTY-backed /shell/exec can
                // allocate a pty inside the guest (Android only sees
                // its own mount namespace otherwise).
                "-b", "/dev",
                "-b", "/proc",
                "-b", "${requireNotNull(rootfs.parentFile).resolve("pi-agent").absolutePath}:/home/seed/.pi/agent",
                // Kill child + descendants when proot exits; without
                // it, killing the proot process leaves uvicorn (and
                // the Flask subprocess it spawned) orphaned.
                "--kill-on-exit",
                "/bin/sh", "-c",
                "cd /home/seed/backend && exec uvicorn seed_backend.service:app --host 127.0.0.1 --port 7777",
            ),
            fake.lastCommand,
        )
    }

    @Test
    fun startNeverAddsForeignArchitectureEmulationFlag() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs-native")
        val proot = tempFolder.newFile("proot")
        val fake = RecordingProcessFactory(FakeProcess(stdout = "", stderr = ""))

        ProotRunner(
            prootExecutable = proot,
            rootfsDir = rootfs,
            factory = fake,
        ).start(this)

        assertFalse(fake.lastCommand.orEmpty().contains("-q"))
        assertFalse(fake.lastCommand.orEmpty().any { it.contains("qemu", ignoreCase = true) })
    }

    @Test
    fun startUsesRootfsAsWorkingDirectory() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val fake = RecordingProcessFactory(FakeProcess(stdout = "", stderr = ""))

        ProotRunner(prootExecutable = proot, rootfsDir = rootfs, factory = fake)
            .start(this)

        // Proot's `-r` does the chroot; the Process CWD is just where
        // proot itself starts. We pick the rootfs dir as a
        // convention — keeps the proot's logs / tmp files scoped
        // under the extracted runtime.
        assertEquals(rootfs, fake.lastWorkingDir)
    }

    @Test
    fun startPassesEnvironmentToFactory() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val fake = RecordingProcessFactory(FakeProcess(stdout = "", stderr = ""))
        val env = mapOf("TERM" to "dumb", "PATH" to "/usr/bin:/bin")

        ProotRunner(prootExecutable = proot, rootfsDir = rootfs, env = env, factory = fake)
            .start(this)

        assertEquals(env, fake.lastEnv)
    }

    @Test
    fun handleIsAliveDelegatesToProcess() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(stdout = "", stderr = "")
        val fake = RecordingProcessFactory(process)

        val handle = ProotRunner(proot, rootfs, factory = fake).start(this)

        assertTrue(handle.isAlive)
        process.simulateExit(0)
        assertFalse(handle.isAlive)
    }

    @Test
    fun handleEmitsStdoutLinesAsTheyAreRead() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(
            stdout = "INFO:     Started server process [1]\nINFO:     Uvicorn running on http://127.0.0.1:7777\n",
            stderr = "",
        )
        val fake = RecordingProcessFactory(process)

        val handle = ProotRunner(proot, rootfs, factory = fake).start(this)

        val lines = handle.stdout.take(2).toList()
        assertEquals(
            listOf(
                "INFO:     Started server process [1]",
                "INFO:     Uvicorn running on http://127.0.0.1:7777",
            ),
            lines,
        )
    }

    @Test
    fun handleEmitsStderrLinesSeparatelyFromStdout() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(
            stdout = "ok\n",
            stderr = "WARN: deprecated\nERROR: connection refused\n",
        )
        val fake = RecordingProcessFactory(process)

        val handle = ProotRunner(proot, rootfs, factory = fake).start(this)

        val stdout = handle.stdout.first()
        val stderrLines = handle.stderr.take(2).toList()
        assertEquals("ok", stdout)
        assertEquals(listOf("WARN: deprecated", "ERROR: connection refused"), stderrLines)
    }

    @Test
    fun handleEmitsLastLineEvenWithoutTrailingNewline() = runTest(UnconfinedTestDispatcher()) {
        // BufferedReader.readLine() returns the partial line at EOF
        // even if there's no terminating '\n'. This matters because
        // uvicorn's "Uvicorn running on ..." line is the last line
        // of its startup output and we don't want to lose it.
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(stdout = "no newline here", stderr = "")
        val fake = RecordingProcessFactory(process)

        val handle = ProotRunner(proot, rootfs, factory = fake).start(this)

        assertEquals("no newline here", handle.stdout.first())
    }

    @Test
    fun stdoutFlowCompletesAtEof() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(stdout = "first\nsecond\n", stderr = "")
        val handle = ProotRunner(
            prootExecutable = proot,
            rootfsDir = rootfs,
            factory = RecordingProcessFactory(process),
        ).start(this)

        val lines = withTimeout(1_000) { handle.stdout.toList() }

        assertEquals(listOf("first", "second"), lines)
    }

    @Test
    fun stderrFlowCompletesAtEof() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(stdout = "", stderr = "warning\nfailed\n")
        val handle = ProotRunner(
            prootExecutable = proot,
            rootfsDir = rootfs,
            factory = RecordingProcessFactory(process),
        ).start(this)

        val lines = withTimeout(1_000) { handle.stderr.toList() }

        assertEquals(listOf("warning", "failed"), lines)
    }

    @Test
    fun destroyCallsProcessDestroy() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(stdout = "", stderr = "")
        val fake = RecordingProcessFactory(process)

        val handle = ProotRunner(proot, rootfs, factory = fake).start(this)
        handle.destroy()

        assertTrue(process.destroyed)
    }

    @Test
    fun destroyEscalatesWhenProcessDoesNotExitAfterSigterm() = runTest(UnconfinedTestDispatcher()) {
        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        val process = FakeProcess(stdout = "", stderr = "", exitsOnDestroy = false)
        val fake = RecordingProcessFactory(process)

        val handle = ProotRunner(
            proot,
            rootfs,
            factory = fake,
            terminationGracePeriodMs = 1,
        ).start(this)
        handle.destroy()

        assertTrue(
            "destroyForcibly should follow an unsuccessful graceful wait",
            process.forciblyDestroyed.await(1, TimeUnit.SECONDS),
        )
        assertFalse(process.isAlive)
    }

    @Test
    fun drainClosesCleanlyWhenProcessDestroyInterruptsRead() = runTest(UnconfinedTestDispatcher()) {
        // Reproduces the on-device crash from opening Settings:
        // RuntimeSupervisor.replaceGeneration() calls handle.destroy() from a
        // coroutine on the same scope as the drain; destroy() closes the
        // process pipes from a different thread, and the drain's
        // BufferedReader.readLine() throws InterruptedIOException. Before the
        // fix, that exception escaped the coroutine and crashed the whole
        // service process. After the fix, the drain swallows the
        // InterruptedIOException and completes normally.
        //
        // runTest swallows uncaught exceptions by default, so we capture the
        // exception via a CoroutineExceptionHandler installed on a child
        // scope; the test fails if the handler ever fires.
        val captured = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, exc -> captured += exc }
        val parentScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + handler)

        val rootfs = tempFolder.newFolder("rootfs")
        val proot = tempFolder.newFile("proot")
        // Pre-set the interrupt so the very first BufferedReader.fill() throws.
        val stdout = InterruptingInputStream("ignored\n")
        stdout.interruptNextRead()
        val stderr = InterruptingInputStream("")
        val process = InterruptibleFakeProcess(stdout, stderr)
        val fake = RecordingProcessFactory(process)

        ProotRunner(proot, rootfs, factory = fake).start(parentScope)

        // Wait for the drain coroutine to observe the InterruptedIOException
        // and settle. If it swallowed the exception cleanly, no captured
        // exception is recorded; if it propagated, the handler fires.
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        assertEquals(
            "drain must swallow InterruptedIOException, not propagate to the scope: $captured",
            emptyList<Throwable>(),
            captured,
        )

        parentScope.cancel()
    }
}

/** InputStream that throws InterruptedIOException once after [interruptNextRead] is invoked.
 *
 *  Used to reproduce what `Process.destroy()` does on Android when called from
 *  a thread other than the drain's: it closes the pipes, and Android's pipe
 *  layer raises InterruptedIOException to the readLine() caller.
 */
private class InterruptingInputStream(
    initialData: String,
) : InputStream() {
    private val buffer = initialData.toByteArray(Charsets.UTF_8)
    private var pos = 0
    @Volatile private var interrupted = false

    fun interruptNextRead() { interrupted = true }

    override fun read(): Int {
        if (interrupted) throw InterruptedIOException("simulated close()")
        if (pos >= buffer.size) return -1
        return buffer[pos++].toInt()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (interrupted) throw InterruptedIOException("simulated close()")
        if (pos >= buffer.size) return -1
        val n = minOf(len, buffer.size - pos)
        for (i in 0 until n) {
            b[off + i] = buffer[pos++]
        }
        return n
    }
}

/** FakeProcess that exposes the supplied InputStreams directly (no ByteArrayInputStream wrapping). */
private class InterruptibleFakeProcess(
    private val stdoutStream: InputStream,
    private val stderrStream: InputStream,
) : Process() {
    private val alive = AtomicBoolean(true)
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = stdoutStream
    override fun getErrorStream(): InputStream = stderrStream
    override fun waitFor(): Int { /* unused */ throw InterruptedException() }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
    override fun exitValue(): Int = 0
    override fun destroy() { alive.set(false) }
    override fun destroyForcibly(): Process { alive.set(false); return this }
    override fun isAlive(): Boolean = alive.get()
}

// ---- Test doubles -------------------------------------------------------

/**
 * Captures the most recent `(command, workingDir, env)` triple and
 * returns a fixed [Process] for every call. There is no concurrent
 * access — the runner is single-threaded in the tests.
 */
private class RecordingProcessFactory(
    private val process: Process,
) : ProcessFactory {
    var lastCommand: List<String>? = null
        private set
    var lastWorkingDir: java.io.File? = null
        private set
    var lastEnv: Map<String, String>? = null
        private set

    override fun start(
        command: List<String>,
        workingDir: java.io.File?,
        environment: Map<String, String>,
    ): Process {
        lastCommand = command
        lastWorkingDir = workingDir
        lastEnv = environment
        return process
    }
}

/**
 * Minimal in-memory [Process] for JVM tests.
 *
 * Implements the [Process] abstract class as a no-op shell around
 * canned byte streams. The exit latch starts at 1 — [simulateExit]
 * counts it down to 0 to flip [isAlive] / [waitFor]. The output
 * streams are always-wrapping `ByteArrayInputStream`s of the
 * canned bytes (no real IO, no real process).
 *
 * **Android note:** this fake subclasses the *JVM* `Process`,
 * which has a richer API than the Android stub. We override only
 * the methods that exist in both (the ones the production code
 * uses: `getInputStream`, `getErrorStream`, `isAlive`, `destroy`,
 * `destroyForcibly`, `waitFor`, `exitValue`, `getOutputStream`).
 * The production code does not call `pid()`, `toHandle()`,
 * `children`, `descendants`, or `supportsNormalTermination` —
 * Android's `Process` doesn't have those, and we don't need them.
 */
private class FakeProcess(
    stdout: String,
    stderr: String,
    private val exitsOnDestroy: Boolean = true,
) : Process() {

    private val stdoutBytes = stdout.toByteArray(Charsets.UTF_8)
    private val stderrBytes = stderr.toByteArray(Charsets.UTF_8)
    private val exitLatch = CountDownLatch(1)
    private val alive = AtomicBoolean(true)
    private val stdin = ByteArrayOutputStream()
    private var exitCode: Int = 0
    val destroyed: Boolean get() = !alive.get()
    val forciblyDestroyed = CountDownLatch(1)

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutBytes)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(stderrBytes)

    override fun waitFor(): Int {
        exitLatch.await()
        return exitCode
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
        exitLatch.await(timeout, unit)

    override fun exitValue(): Int {
        if (exitLatch.count > 0) {
            throw IllegalThreadStateException("process has not exited")
        }
        return exitCode
    }

    override fun destroy() {
        if (exitsOnDestroy) simulateExit(143)
    }

    override fun destroyForcibly(): Process {
        forciblyDestroyed.countDown()
        simulateExit(137)
        return this
    }

    override fun isAlive(): Boolean = alive.get()

    /** Test helper: pretend the child exited. */
    fun simulateExit(code: Int) {
        if (alive.compareAndSet(true, false)) {
            exitCode = code
            exitLatch.countDown()
        }
    }
}
