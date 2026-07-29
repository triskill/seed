package com.seed.app.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
                "/bin/sh", "-c",
                "cd /home/seed/backend && exec uvicorn seed_backend.service:app --host 127.0.0.1 --port 7777",
            ),
            fake.lastCommand,
        )
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
