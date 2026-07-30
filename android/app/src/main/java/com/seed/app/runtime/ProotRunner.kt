package com.seed.app.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
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
 * uvicorn the foreground process inside proot, so SIGTERM from
 * `handle.destroy()` reaches uvicorn directly (not a bash
 * wrapper) and the proot child exits as soon as uvicorn exits.
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
        val command = listOf(
            prootExecutable.absolutePath,
            "-r", rootfsDir.absolutePath,
            "/bin/sh", "-c",
            LAUNCH_COMMAND,
        )
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
            if (!stopping.compareAndSet(false, true)) return
            process.destroy()

            // Service.onDestroy runs on the main thread, so waiting there
            // would risk an ANR. A short-lived daemon watcher provides the
            // graceful deadline and escalates independently of the service's
            // coroutine scope, which is cancelled during teardown.
            Thread({
                try {
                    if (!process.waitFor(terminationGracePeriodMs, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (process.isAlive) process.destroyForcibly()
                }
            }, "seed-proot-stop").apply {
                isDaemon = true
                start()
            }
        }

        private suspend fun drain(stream: InputStream, sink: Channel<String>) {
            try {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        sink.send(line)
                        line = reader.readLine()
                    }
                }
            } finally {
                sink.close()
            }
        }
    }

    private companion object {
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
 * The PID isn't actionable for our use case (we never need to
 * `kill -9` by PID — `handle.destroy()` goes through
 * [Process.destroy] which uses the handle), so we drop it
 * rather than resort to a `sh -c 'echo $$; ...'` trick to
 * recover the PID from stdout. A v0.2 task can add it back
 * with that trick if logcat ever needs to correlate lines
 * with a PID.
 */
interface ProotHandle {
    val isAlive: Boolean
    val stdout: Flow<String>
    val stderr: Flow<String>

    /** Sends SIGTERM and asynchronously escalates after a five-second grace period. */
    fun destroy()
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
