package cz.trety.seed.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spawns the proot process and exposes its lifetime as a
 * [ProotHandle].
 *
 * **Process model.** The Android foreground service
 * ([RuntimeService]) owns the [CoroutineScope] passed to
 * [start]; the scope is what keeps the stream-draining
 * coroutines alive. Each output flow is backed by a bounded,
 * closeable channel and completes when its process stream reaches
 * EOF (or its drain is cancelled), so collectors from replaced
 * processes do not accumulate. The [ProotHandle] is a thin query
 * surface — it does not own the scope, so service teardown can
 * cancel all remaining work in one place.
 *
 * **Why a [ProcessFactory] seam.** The `java.lang.Process` class
 * is hard to fake in unit tests (it's an abstract class with
 * many methods, including some added in newer JDKs). A factory
 * interface keeps the production code trivial
 * ([JvmProcessFactory] just calls `ProcessBuilder.start()`) and
 * the test code in full control of what gets "started".
 *
 * **Command construction.** The `exec uvicorn ...` shell
 * command is the v0.1 launch — matches `backend/scripts/dev.sh`
 * (minus `--reload`, which spawns an extra watcher process we
 * don't want inside proot). The `exec` is important: it makes
 * uvicorn the initial guest process rather than a shell wrapper. Production
 * shutdown uses an owned tracer identity and PRoot's QUIT/CONT cleanup handler;
 * TERM is ignored by the pinned PRoot, and Java force-destroy is not a portable
 * Android SIGKILL guarantee.
 *
 * **Networking.** Proot shares the network namespace with the
 * parent by default, so `127.0.0.1:7777` inside proot is
 * `127.0.0.1:7777` on the device. No `-b 0.0.0.0` is needed (and
 * is *not* used — see docs/plans/2026-07-03-embedded-runtime.md
 * §2.1 for the security reasoning).
 */
class ProotRunner(
    private val prootExecutable: File,
    private val rootfsDir: File,
    private val workDir: File = rootfsDir,
    private val env: Map<String, String> = System.getenv(),
    private val factory: ProcessFactory = JvmProcessFactory,
    private val terminationGracePeriodMs: Long = 5_000,
) {

    fun start(scope: CoroutineScope): ProotHandle {
        val baseArgs = ProotCommand.base(
            prootExecutable,
            rootfsDir,
        )
        val command = baseArgs.toMutableList().apply {
            add("/bin/sh")
            add("-c")
            add(LAUNCH_COMMAND)
        }
        val process = factory.start(command, workDir, env)
        return ProcessHandleImpl(process, scope, terminationGracePeriodMs)
    }

    private class ProcessHandleImpl(
        private val process: Process,
        scope: CoroutineScope,
        private val terminationGracePeriodMs: Long,
    ) : ProotHandle {
        private val stdoutLines = Channel<String>(capacity = EARLY_OUTPUT_BUFFER_LINES)
        private val stderrLines = Channel<String>(capacity = EARLY_OUTPUT_BUFFER_LINES)
        private val stdoutFlow = stdoutLines.receiveAsFlow()
        private val stderrFlow = stderrLines.receiveAsFlow()
        private val stopping = AtomicBoolean(false)

        init {
            // We inherit the scope's dispatcher (production: a
            // `SupervisorJob + Dispatchers.IO` scope owned by the
            // foreground service; tests: the `TestScope`'s
            // `UnconfinedTestDispatcher`) so `readLine()` runs on a
            // thread appropriate to the caller. Each channel keeps
            // up to 64 early lines during the gap between launching
            // these drains and RuntimeService subscribing. A full
            // channel suspends the drain, applying backpressure
            // instead of allowing runaway log output to grow memory.
            // drain() closes the channel at EOF so service collectors
            // naturally finish when a process exits or is replaced.
            scope.launch { drain(process.inputStream, stdoutLines) }
            scope.launch { drain(process.errorStream, stderrLines) }
        }

        override val isAlive: Boolean get() = process.isAlive
        override val stdout: Flow<String> = stdoutFlow
        override val stderr: Flow<String> = stderrFlow
        override fun destroy() {
            if (!stopping.compareAndSet(false, true)) {
                // Failed admission/inspection may be retried, without duplicate in-flight workers.
                if (process is OwnedRuntimeProcess) process.destroy()
                return
            }
            // Owned Android processes schedule verified QUIT/CONT here without
            // touching pipes. Signal delivery must never wait for a blocked read.
            process.destroy()
            // Legacy JVM factories retain their platform policy. Owned processes
            // deliberately do not turn force-destroy into tracer-only SIGKILL.
            Thread({
                try {
                    if (!process.waitFor(terminationGracePeriodMs, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (process.isAlive) process.destroyForcibly()
                } finally {
                    if (!process.isAlive) closeExitedProcessPipes(process)
                }
            }, "seed-proot-stop").apply {
                isDaemon = true
                start()
            }
        }

        private suspend fun drain(stream: InputStream, sink: Channel<String>) {
            try {
                // The process owner handles pipe closing on bounded cleanup workers.
                // Closing here during cancellation could block before sink.close().
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                var line = reader.readLine()
                while (line != null) {
                    sink.send(line)
                    line = reader.readLine()
                }
            } catch (closed: IOException) {
                // destroy() on the parent handle closes the process pipes from
                // another thread; Android's pipe layer throws
                // InterruptedIOException to the readLine() caller in that
                // case. The drain's coroutine is launched on the service
                // scope (a SupervisorJob), but the IOException still escapes
                // into the dispatcher and would otherwise reach the JVM's
                // default uncaught handler and crash the process. Treat
                // stream closure as the natural end of this drain: the
                // channel's collectors see an EOF. EOF alone does not authorize
                // replacement; the supervisor separately confirms handle exit. The IOException is intentionally not
                // logged because every generation replacement triggers one.
            } finally {
                sink.close()
            }
        }
    }

    private companion object {
        // A blocking close cannot stall signal delivery or allocate unbounded workers.
        private val pipeCleanupPermits = Semaphore(4)
        fun closeExitedProcessPipes(process: Process) {
            if (!pipeCleanupPermits.tryAcquire()) return
            try {
                Thread({
                    try {
                        runCatching { process.outputStream.close() }
                        runCatching { process.inputStream.close() }
                        runCatching { process.errorStream.close() }
                    } finally { pipeCleanupPermits.release() }
                }, "seed-runtime-pipe-cleanup").apply { isDaemon = true }.start()
            } catch (failure: Exception) {
                pipeCleanupPermits.release()
                throw failure
            }
        }

        const val EARLY_OUTPUT_BUFFER_LINES = 64

        // The shell command run inside proot. Hard-coded for v0.1
        // — see class KDoc and docs/plans/2026-07-03-embedded-runtime.md
        // §2.3 for why we don't extract it to a rootfs script yet.
        const val LAUNCH_COMMAND =
            "cd /home/seed/backend && exec uvicorn seed_backend.service:app --host 127.0.0.1 --port 7777"
    }
}

/**
 * Live handle to a running proot process. Returned by
 * [ProotRunner.start]; the caller is responsible for the
 * [CoroutineScope] that owns the underlying stream-draining
 * coroutines. [stdout] and [stderr] each complete when their
 * corresponding process stream reaches EOF.
 *
 * **No `pid` field** — Android's [java.lang.Process] does not
 * expose `pid()` (it was added in JDK 9 but not to the Android
 * API), and the standard `Process.toHandle()` is also missing.
 * Production captures a verified PID/start-time receipt in a separate owned
 * factory, not application stdout. That identity is internal to shutdown and
 * is deliberately not exposed through the UI-facing handle.
 */
interface ProotHandle {
    val isAlive: Boolean
    val stdout: Flow<String>
    val stderr: Flow<String>

    /** Request shutdown; owned Android runtimes use QUIT/CONT, not Java TERM/KILL. */
    fun destroy()

    /** Await the handle's exit observation; Android ownership still needs device validation. */
    suspend fun awaitExit(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (isAlive) delay(100)
        true
    } ?: false
}

/**
 * Strategy for spawning the proot process. The default
 * [JvmProcessFactory] uses `ProcessBuilder` from the JDK; tests
 * inject a recording fake to assert the exact command and
 * control the process's streams.
 */
interface ProcessFactory {
    fun start(
        command: List<String>,
        workingDir: File?,
        environment: Map<String, String>,
    ): Process
}

object JvmProcessFactory : ProcessFactory {
    override fun start(
        command: List<String>,
        workingDir: File?,
        environment: Map<String, String>,
    ): Process {
        val pb = ProcessBuilder(command)
        if (workingDir != null) pb.directory(workingDir)
        // Replace the child's env wholesale (don't merge with
        // the parent's). Proot inherits the merged env into the
        // chrooted shell; the only thing we need to set explicitly
        // is what the caller passed in (typically TERM=dumb,
        // PATH, HOME). Anything the caller didn't set is dropped
        // — that's intentional, it's how the existing
        // `dev.sh` script behaves (no `export` calls in v0.1).
        pb.environment().clear()
        pb.environment().putAll(environment)
        return pb.start()
    }
}
