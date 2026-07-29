package com.seed.app.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Spawns the proot process and exposes its lifetime as a
 * [ProotHandle].
 *
 * **Process model.** The Android foreground service
 * ([RuntimeService]) owns the [CoroutineScope] passed to
 * [start]; the scope is what keeps the stream-draining
 * coroutines alive. The [ProotHandle] is a thin query surface —
 * it does not own the scope, so the service can stop the runtime
 * by cancelling the scope (which kills the coroutines + lets the
 * [Process] be reaped when proot exits) and start a new one
 * without leaking handles.
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
) {

    fun start(scope: CoroutineScope): ProotHandle {
        val command = listOf(
            prootExecutable.absolutePath,
            "-r", rootfsDir.absolutePath,
            "/bin/sh", "-c",
            LAUNCH_COMMAND,
        )
        val process = factory.start(command, workDir, env)
        return ProcessHandleImpl(process, scope)
    }

    private class ProcessHandleImpl(
        private val process: Process,
        scope: CoroutineScope,
    ) : ProotHandle {
        private val stdoutFlow = MutableSharedFlow<String>(replay = 64)
        private val stderrFlow = MutableSharedFlow<String>(replay = 64)

        init {
            // The drain coroutines are the only reason the handle
            // is "alive" in any meaningful sense: if they stop,
            // stdout/stderr flow emissions stop. We inherit the
            // scope's dispatcher (production: a
            // `SupervisorJob + Dispatchers.IO` scope owned by
            // the foreground service; tests: the `TestScope`'s
            // `UnconfinedTestDispatcher`) so `readLine()` runs on
            // a thread appropriate to the caller. The 64-line
            // replay buffer prevents fast startup output from
            // being lost in the small gap between start() launching
            // these drains and RuntimeService subscribing to the
            // returned handle. Once a subscriber exists, a full
            // buffer suspends emission and throttles a runaway log
            // producer instead of growing memory without bound.
            scope.launch { drain(process.inputStream, stdoutFlow) }
            scope.launch { drain(process.errorStream, stderrFlow) }
        }

        override val isAlive: Boolean get() = process.isAlive
        override val stdout: Flow<String> = stdoutFlow.asSharedFlow()
        override val stderr: Flow<String> = stderrFlow.asSharedFlow()
        override fun destroy() {
            process.destroy()
        }

        private suspend fun drain(stream: InputStream, sink: MutableSharedFlow<String>) {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    sink.emit(line)
                    line = reader.readLine()
                }
            }
        }
    }

    private companion object {
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
 * coroutines.
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

    /** Sends SIGTERM. Does not block. */
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
