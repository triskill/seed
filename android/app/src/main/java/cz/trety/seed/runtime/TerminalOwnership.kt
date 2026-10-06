package cz.trety.seed.runtime

import android.os.Looper
import com.termux.terminal.TerminalSession
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal fun verifyTerminalReceipt(
    receipt: String, nonce: String, ptyPid: Int, parentPid: Int, uid: Int,
    inspect: (Int) -> ProcessInspection,
): OwnedProcessIdentity {
    val lines = receipt.lines()
    check(lines.size == 3 && lines[0] == nonce && lines[2].isEmpty()) { "Invalid terminal receipt" }
    val claimed = parseProcIdentity(lines[1], "Uid: $uid $uid $uid $uid\n")
    val actual = inspect(claimed.identity.pid)
    check(ptyPid > 1 && claimed.identity.pid == ptyPid && claimed.parentPid == parentPid &&
        actual is ProcessInspection.Present && actual.identity == claimed.identity &&
        actual.parentPid == parentPid && actual.state != 'Z') { "Could not establish terminal ownership" }
    return claimed.identity
}

/** A receipt is never read through a symlink or allowed to allocate an unbounded buffer. */
internal fun readTerminalOwnershipFile(file: File): String {
    check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    return file.inputStream().use { input ->
        val bytes = ByteArray(4097)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        check(size in 1..4096) { "Invalid terminal ownership file size" }
        bytes.copyOf(size).toString(Charsets.UTF_8)
    }
}

/** Termux's waiter posts waitpid completion to its main Handler. PID 0 is NOT an exit. */
internal class TerminalSessionProcess(private val session: TerminalSession) : Process() {
    private fun <T> onMain(action: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else runBlocking { withContext(Dispatchers.Main) { action() } }
    override fun isAlive(): Boolean = onMain { session.isRunning }
    override fun exitValue(): Int = onMain {
        if (session.isRunning) throw IllegalThreadStateException("Terminal has not exited")
        session.exitStatus
    }
    override fun waitFor(): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Cannot wait for terminal on main" }
        while (isAlive) Thread.sleep(20)
        return exitValue()
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Cannot wait for terminal on main" }
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (isAlive) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(20)
        }
        return true
    }
    // Only an unacknowledged, fixed host wrapper may call this method.
    override fun destroy() = onMain { if (session.pid > 1) session.finishIfRunning() }
    override fun getInputStream(): java.io.InputStream = error("PTY streams are managed by Termux")
    override fun getErrorStream(): java.io.InputStream = error("PTY streams are managed by Termux")
    override fun getOutputStream(): java.io.OutputStream = error("PTY streams are managed by Termux")
}

/** IO-only handshake; retains the post-ACK owned handle even when accepted is missing. */
internal class TerminalOwnership(
    receiptRoot: File,
    private val prootPath: String,
    private val parentPid: Int,
    private val uid: Int,
    private val inspect: (Int) -> ProcessInspection,
    private val signal: (Int, OwnedProcessSignal) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val cleanupReady: (OwnedProcessIdentity, String) -> Boolean = ::hasQuitCleanupHandler,
    private val schedule: (() -> Unit) -> Boolean = RuntimeSignalWorkers::schedule,
) {
    val directory: File
    val nonce: String = UUID.randomUUID().toString()
    @Volatile private var process: Process? = null
    @Volatile private var acknowledged = false
    init {
        check(receiptRoot.isDirectory || receiptRoot.mkdirs())
        directory = Files.createTempDirectory(receiptRoot.toPath(), "launch-").toFile()
    }
    fun argv(command: List<String>): Array<String> =
        (listOf("/system/bin/sh", "-c", OwnedRuntimeProcessFactory.HANDSHAKE_SCRIPT,
            "seed-terminal-launch", directory.absolutePath, nonce) + command).toTypedArray()

    fun establish(raw: Process, ptyPid: Int, timeoutMillis: Long = 10_000) {
        process = raw
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun await(file: File) {
            while (!file.exists()) {
                check(raw.isAlive && System.nanoTime() < deadline) { "Terminal handshake timed out or exited" }
                Thread.sleep(10)
            }
        }
        try {
            val receipt = File(directory, "receipt")
            await(receipt)
            val identity = verifyTerminalReceipt(readTerminalOwnershipFile(receipt), nonce,
                ptyPid, parentPid, uid, inspect)
            val owned = OwnedRuntimeProcess(raw, identity, inspect, signal, onFailure = onFailure,
                cleanupReady = { cleanupReady(identity, prootPath) }, schedule = schedule)
            File(directory, "ack.tmp").writeText("$nonce\n")
            // Publish the owned policy BEFORE the ack can permit exec, including exceptional paths.
            process = owned
            acknowledged = true
            check(File(directory, "ack.tmp").renameTo(File(directory, "ack")))
            val accepted = File(directory, "accepted")
            await(accepted)
            check(readTerminalOwnershipFile(accepted) == "$nonce\n")
        } catch (failure: Exception) {
            onFailure(failure)
            destroyOwned()
        } finally {
            directory.deleteRecursively()
        }
    }
    fun destroyOwned() {
        val current = process ?: return
        if (acknowledged) current.destroy()
        else if (!schedule { runCatching { current.destroy() }.onFailure(onFailure) }) {
            onFailure(IllegalStateException("Terminal cleanup workers are busy"))
        }
    }
    fun discardUnstarted() { check(process == null); directory.deleteRecursively() }
}
