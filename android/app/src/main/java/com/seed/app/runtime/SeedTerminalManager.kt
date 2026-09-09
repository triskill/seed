package com.seed.app.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import com.termux.terminal.TerminalSession

/**
 * Owns the lifecycle of the interactive shell [TerminalSession].
 *
 * **Thread-local session creation.** The constructor stores
 * configuration (rootfs, native libraries, architecture).
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
 * layout, mount bindings, and architecture emulation.
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

    /**
     * Terminal client with clipboard input/output.
     * Created once and reused for the session lifetime.
     */
    private val terminalClient by lazy {
        SeedTerminalClient(applicationContext)
    }

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
    fun getOrCreateSession(): TerminalSession {
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
    fun attachView(view: com.termux.view.TerminalView) {
        terminalClient.attachView(view)
        view.setTerminalViewClient(terminalClient)
        view.attachSession(getOrCreateSession())
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
        terminalClient.detachView(view)
        view.setTerminalViewClient(null)
    }

    /**
     * Permanently stop the terminal session and release resources.
     * Called when the RuntimeService is destroyed.
     */
    @Synchronized
    fun close() {
        session?.let { s ->
            try {
                s.finishIfRunning()
            } catch (e: Exception) {
                Log.w(TAG, "Error finishing terminal session", e)
            }
            session = null
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
     * QEMU architecture emulation is configured identically to
     * the backend PRoot process (via ProotCommand base).
     */
    @Synchronized
    private fun createSession(): TerminalSession {
        // Resolve native proot installation
        val installation = NativeProot.resolve(nativeLibraryDir.absolutePath)

        // Resolve QEMU if the rootfs architecture differs from the host
        val qemuExecutable = try {
            val version = RootfsVersion.parse(
                File(rootfsDir.parent, VERSION_FILE).readText()
            )
            if (version.guestArchitecture.requiresQemuX86_64(Build.SUPPORTED_ABIS)) {
                NativeProot.resolveQemuX86_64(nativeLibraryDir.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine guest architecture for terminal QEMU, no QEMU: ${e.message}")
            null
        }

        // Build command arguments using shared base
        val baseArgs = ProotCommand.base(
            executable = installation.executable,
            rootfsDir = rootfsDir,
            qemuX86_64Executable = qemuExecutable,
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

        return TerminalSession(
            installation.executable.absolutePath,  // shellPath (proot executable)
            "/",                                  // host cwd → guest root under PRoot
            args,                                  // args
            envArray,                              // env
            10_000,                                // transcriptRows (max buffer history)
            terminalClient,                        // client
        )
    }

    /** Install the no-fork interactive command bridge inside the writable rootfs. */
    private fun installTerminalRepl(): File {
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
        const val VERSION_FILE = ".version"
        const val CACHE_DIR_NAME = "proot"

        // This executes one command at a time through Python's Popen path,
        // which uses the Android-compatible spawn mechanism. Guest ash's
        // native fork(2) is intentionally unavailable under PRoot.
        const val TERMINAL_REPL = """
import os
import subprocess
import sys

cwd = "/home/seed"

def show_prompt():
    sys.stdout.write("seed:" + cwd + "# ")
    sys.stdout.flush()

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
    show_prompt()
    line = sys.stdin.readline()
    if not line:
        break
    command = line.strip()
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
    except OSError as error:
        print("seed: " + str(error), file=sys.stderr)
"""
    }
}
