package com.seed.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import android.system.Os
import android.system.OsConstants
import java.lang.ref.WeakReference
import com.termux.terminal.TerminalSessionClient

/**
 * Owns the lifecycle of the interactive shell [TerminalSession].
 *
 * **Thread-local session creation.** The constructor stores
 * configuration (rootfs and native libraries).
 * The actual [TerminalSession] is not created until the first
 * call to [getOrCreateSession] — which happens the first time
 * the Shell tab is entered (lazy initialization).
 *
 * **Lazy session lifecycle.** Termux does not create the
 * subprocess immediately when [TerminalSession] is constructed.
 * The PTY subprocess starts when the view attaches and the
 * session size becomes known. This fits Seed's nav
 * re-creation semantics perfectly: no process until the shell
 * tab is visited.
 *
 * **Attach/detach.** The [TerminalView] is connected to this
 * manager through [attachView] and [detachView]. These methods
 * manage the [TerminalSessionClient] ↔ [TerminalViewClient]
 * bridge safely, without retaining strong references to
 * views or their enclosing activities.
 *
 * **Lifecycle boundary.** The session survives:
 * - Activity destruction
 * - Navigation away from Shell tab
 * - TerminalView destruction
 * Only explicit [close()] (service destruction) or the
 * session's own completion stops it.
 *
 * **Architecture reuse.** Uses the same PRoot arguments as
 * [ProotRunner.backend] (via [ProotCommand.base]) plus its
 * own environment via [ProotEnvironment.createTerminal()],
 * ensuring both child processes start with identical rootfs
 * layout and mount bindings.
 */
class SeedTerminalManager(
    /** Application context (process-bound, survives navigation). */
    private val applicationContext: Context,
) {
    private val TAG = "SeedTerminalManager"

    // -- Configuration --

    /** Path to the rootfs directory (`filesDir/linux/rootfs`). */
    private val rootfsDir: File by lazy {
        File(applicationContext.filesDir, "linux").resolve("rootfs")
    }

    /** Native library directory where proot binaries live (`applicationInfo.nativeLibraryDir`). */
    private val nativeLibraryDir: File by lazy {
        File(applicationContext.applicationInfo.nativeLibraryDir)
    }

    // -- State --

    /**
     * The interactive shell session. Created lazily on first
     * terminal visit, destroyed by [close].
     */
    private var session: TerminalSession? = null
    private var ownership: TerminalOwnership? = null
    private var handshake: Deferred<Unit>? = null
    private var frozen = false
    private var closing = false
    private var attachedView = WeakReference<com.termux.view.TerminalView>(null)
    // Never cancelled before owned cleanup/reaping completes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun requireMain() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Terminal lifecycle must run on the main thread"
        }
    }

    /** Freeze admission AND disconnect lazy views before they can initialize a PID-0 session. */
    @Synchronized
    fun freeze() {
        requireMain()
        frozen = true
        disconnectView()
    }

    @Synchronized
    fun unfreeze() {
        requireMain()
        check(session == null) { "Terminal exit has not been confirmed" }
        frozen = false
        closing = false
    }

    private fun disconnectView() {
        attachedView.get()?.let { view ->
            terminalClient.detachView(view)
            // Pinned Termux exposes these fields. Clearing them prevents delayed updateSize
            // from starting a previously attached, still PID-0 session.
            view.mTermSession = null
            view.mEmulator = null
        }
        attachedView.clear()
    }

    /** No blocking wait on main: Termux's main handler must consume its waitpid result. */
    suspend fun stopAndAwaitExit(timeoutMillis: Long = 10_000): Boolean {
        require(timeoutMillis > 0)
        val target = withContext(Dispatchers.Main.immediate) {
            synchronized(this@SeedTerminalManager) {
                closing = session != null
                disconnectView()
                session
            }
        } ?: return true
        return withTimeoutOrNull(timeoutMillis) {
            val pending = withContext(Dispatchers.Main.immediate) { handshake }
            pending?.await()
            withContext(Dispatchers.Main.immediate) {
                if (target.pid == 0) {
                    // Never initialized and disconnected: no subprocess can exist.
                    releaseConfirmed(target, unstarted = true)
                } else ownership?.destroyOwned()
            }
            while (withContext(Dispatchers.Main.immediate) { target.pid != 0 && target.isRunning }) {
                delay(20)
            }
            withContext(Dispatchers.Main.immediate) { releaseConfirmed(target) }
            true
        } ?: false
    }

    @Synchronized
    private fun releaseConfirmed(target: TerminalSession, unstarted: Boolean = false) {
        if (session !== target) return
        check(unstarted && target.pid == 0 || !target.isRunning)
        if (unstarted) ownership?.discardUnstarted() else {
            // This is Termux's existing main-handler completion facility, not a
            // second waitpid. Pinned JNI does not expose waitpid errors separately.
            target.exitStatus
        }
        disconnectView()
        session = null
        ownership = null
        handshake = null
        closing = false
    }

    private fun beginOwnershipWhenStarted(target: TerminalSession) {
        if (handshake != null) return
        val owner = checkNotNull(ownership)
        handshake = scope.async {
            while (target.pid == 0 && !frozen && !closing) delay(10)
            if (target.pid <= 0) return@async
            val pid = target.pid
            withContext(Dispatchers.IO) { owner.establish(TerminalSessionProcess(target), pid) }
        }
        scope.launch {
            while (session === target && target.isRunning) delay(50)
            if (session === target && !target.isRunning) {
                handshake?.await()
                releaseConfirmed(target)
            }
        }
    }

    /**
     * Terminal client with clipboard input/output.
     * Created once and reused for the session lifetime.
     */
    private val terminalClient by lazy {
        SeedTerminalClient(applicationContext)
    }

    /** Whether the Shell extra-keys Ctrl button is armed for one terminal key. */
    val controlKeyActive: StateFlow<Boolean>
        get() = terminalClient.controlKeyActive

    /** Arm or disarm the one-shot Ctrl modifier used with the software keyboard. */
    fun toggleControlKey(): Boolean = terminalClient.toggleControlKey()

    // -- Lifecycle --

    /**
     * Get or create the terminal session.
     *
     * Returns the existing session if one is active, otherwise
     * creates a new one with the current configuration.
     *
     * @throws IllegalStateException if the session could not be
     *   created (missing rootfs, missing proot files, etc.).
     */
    @Synchronized
    fun getOrCreateSession(): TerminalSession = RuntimeMaintenanceGate.withWriter {
        getOrCreateAdmittedSession()
    }

    /** Caller already holds writer admission; never nest the non-reentrant gate. */
    private fun getOrCreateAdmittedSession(): TerminalSession {
        requireMain()
        check(!frozen && !closing) { "Terminal launches are frozen for maintenance" }
        session?.takeIf { !it.isRunning }?.let { target ->
            check(handshake?.isCompleted != false) { "Terminal ownership handshake is pending" }
            releaseConfirmed(target)
        }
        return session ?: createSession().also { s -> session = s }
    }

    /**
     * Attach a TerminalView to this session.
     *
     * This is the **only** place a [TerminalView] gets connected to
     * the terminal pipeline.  It performs the full setup sequence:
     *
     * 1. [SeedTerminalClient.attachView] — client weakly stores the
     *    view so onTextChanged → onScreenUpdated is possible.
     * 2. [TerminalView.setTerminalViewClient] — routes user input
     *    (keypresses, gestures) back into Termux's session.
     * 3. [TerminalView.attachSession] — starts the PTY subprocess.
     *
     * Termux requires the view client to be set *before*
     * [TerminalView.attachSession] is called.
     */
    @Synchronized
    fun attachView(view: com.termux.view.TerminalView) = RuntimeMaintenanceGate.withWriter {
        requireMain()
        check(!frozen && !closing) { "Terminal attachment is frozen for maintenance" }
        val target = getOrCreateAdmittedSession()
        terminalClient.attachView(view)
        attachedView = WeakReference(view)
        view.setTerminalViewClient(terminalClient)
        view.attachSession(target)
        beginOwnershipWhenStarted(target)
    }

    /**
     * Detach a specific TerminalView from this manager.
     *
     * Clears the client's weak reference to the given view and
     * unsets the view client. The session itself is NOT destroyed —
     * only the UI connection is released. The shell process continues
     * running in the background.
     *
     * idempotent: calling with a detached or wrong view is a no-op.
     */
    @Synchronized
    fun detachView(view: com.termux.view.TerminalView) {
        requireMain()
        terminalClient.detachView(view)
        view.mTermSession = null
        view.mEmulator = null
        if (attachedView.get() === view) attachedView.clear()
    }

    /**
     * Request asynchronous owned cleanup, retaining the session until confirmed exit.
     * Maintenance callers must use freeze() then stopAndAwaitExit() instead.
     */
    @Synchronized
    fun close() {
        requireMain()
        closing = true
        disconnectView()
        scope.launch {
            if (!stopAndAwaitExit()) Log.w(TAG, "Terminal exit could not be confirmed; ownership retained")
        }
    }

    /**
     * Check if this manager has an active session.
     * Useful for conditional UI updates.
     */
    @Synchronized
    fun hasSession(): Boolean = session != null

    /**
     * Create a new TerminalSession with the configured PRoot arguments.
     *
     * Command: proot -r <rootfs> -b /dev -b /proc --kill-on-exit /bin/sh -l
     *
     * Environment includes:
     * - TERM=xterm-256color (for full-color ANSI support)
     * - COLORTERM=truecolor (for 24-bit color terminals)
     * - HOME=/root (standard root home)
     * - SHELL=/bin/sh (tells login shells what shell to start)
     *
     * The native ARM64 architecture is configured identically to
     * the backend PRoot process (via ProotCommand.base).
     */
    @Synchronized
    private fun createSession(): TerminalSession {
        requireMain()
        check(!frozen && !closing) { "Terminal rootfs writes are frozen" }
        GuestDns.sync(applicationContext, rootfsDir)
        // Resolve native proot installation
        val installation = NativeProot.resolve(nativeLibraryDir.absolutePath)

        // Build command arguments using shared native-only base
        val baseArgs = ProotCommand.base(
            executable = installation.executable,
            rootfsDir = rootfsDir,
        )
        installTerminalRepl()
        val args = baseArgs.toMutableList().apply {
            // PRoot under Android cannot implement guest fork(2). A normal
            // interactive ash can therefore run built-ins (pwd, cd) but not
            // external programs (ls, pi). The Python REPL keeps terminal
            // state and uses the same Popen/vfork-compatible launch path as
            // the proven /shell/exec endpoint.
            add("/usr/bin/python3")
            add("-u")
            add("/home/seed/.seed-terminal-repl.py")
        }.toTypedArray()

        // Build environment for the terminal session
        val environment = ProotEnvironment.createTerminal(
            tempDir = File(applicationContext.cacheDir, CACHE_DIR_NAME),
            installation = installation,
        ) + mapOf(
            "HOME" to "/home/seed",
            "PYTHONUNBUFFERED" to "1",
        )
        val envArray = environment.map { (k, v) -> "$k=$v" }.toTypedArray()

        val owner = TerminalOwnership(
            File(applicationContext.cacheDir, "terminal-ownership"),
            installation.executable.absolutePath,
            android.os.Process.myPid(), android.os.Process.myUid(),
            inspect = { inspectProcIdentity(it) },
            signal = { pid, signal ->
                Os.kill(pid, when (signal) {
                    OwnedProcessSignal.QUIT -> OsConstants.SIGQUIT
                    OwnedProcessSignal.CONTINUE -> OsConstants.SIGCONT
                })
            },
            onFailure = { Log.w(TAG, "Terminal ownership/cleanup failed", it) },
        )
        ownership = owner
        val sessionClient = object : TerminalSessionClient by terminalClient {
            override fun onSessionFinished(finishedSession: TerminalSession) {
                terminalClient.onSessionFinished(finishedSession)
                scope.launch {
                    handshake?.await()
                    if (!finishedSession.isRunning) releaseConfirmed(finishedSession)
                }
            }
        }
        return TerminalSession(
            "/system/bin/sh",                      // fixed host wrapper, not the tracer
            "/",                                  // host cwd → guest root under PRoot
            owner.argv(args.toList()),              // fixed positional argv; no interpolation
            envArray,                              // env
            10_000,                                // transcriptRows (max buffer history)
            sessionClient,                         // preserves clipboard/UI callbacks
        )
    }

    /** Install the no-fork interactive command bridge inside the writable rootfs. */
    private fun installTerminalRepl(): File {
        check(!frozen && !closing) { "Terminal rootfs writes are frozen" }
        val script = File(rootfsDir, "home/seed/.seed-terminal-repl.py")
        val parent = script.parentFile
            ?: throw IllegalStateException("Terminal REPL has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("Could not create terminal home: ${parent.absolutePath}")
        }
        if (!script.isFile || script.readText() != TERMINAL_REPL) {
            script.writeText(TERMINAL_REPL)
        }
        return script
    }

    private companion object {
        const val CACHE_DIR_NAME = "proot"

        // This executes one command at a time through Python's Popen path,
        // which uses the Android-compatible spawn mechanism. Guest ash's
        // native fork(2) is intentionally unavailable under PRoot.
        const val TERMINAL_REPL = """
import os
import readline
import subprocess

cwd = "/home/seed"

def change_directory(argument):
    global cwd
    target = argument.strip() or os.environ.get("HOME", "/home/seed")
    target = os.path.expanduser(target)
    if not os.path.isabs(target):
        target = os.path.join(cwd, target)
    target = os.path.realpath(target)
    if os.path.isdir(target):
        cwd = target
    else:
        print("cd: " + argument + ": No such directory", file=sys.stderr)

print("Seed terminal — " + cwd)
while True:
    try:
        # Alpine's Python readline module provides line editing and history,
        # so the terminal's arrow-key escape sequences edit the command instead
        # of being copied into the command text.
        command = input("seed:" + cwd + "# ").strip()
    except EOFError:
        break
    except KeyboardInterrupt:
        # Ctrl+C at the prompt should cancel the current line, not terminate
        # this PRoot session (which would make the terminal appear to crash).
        print()
        continue
    if not command:
        continue
    if command in ("exit", "logout"):
        break
    if command == "pwd":
        print(cwd)
        continue
    if command == "cd" or command.startswith("cd "):
        change_directory(command[2:])
        continue
    try:
        subprocess.run(["/bin/sh", "-c", command], cwd=cwd, check=False)
    except KeyboardInterrupt:
        # The foreground command was interrupted. Return to a fresh prompt
        # while keeping the command bridge and its PRoot process alive.
        print()
    except OSError as error:
        print("seed: " + str(error), file=sys.stderr)
"""
    }
}
