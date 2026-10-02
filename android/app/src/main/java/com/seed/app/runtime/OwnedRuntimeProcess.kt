package com.seed.app.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class OwnedProcessIdentity(val pid: Int, val startTicks: Long, val uid: Int)

internal sealed interface ProcessInspection {
    data class Present(
        val identity: OwnedProcessIdentity,
        val parentPid: Int,
        val state: Char,
        val quitHandlerInstalled: Boolean = false,
    ) : ProcessInspection
    data object Gone : ProcessInspection
    data object Unknown : ProcessInspection
}

internal enum class OwnedProcessSignal { QUIT, CONTINUE }

/** comm may contain spaces and parentheses; field 22 is indexed from field 3. */
internal fun parseProcIdentity(stat: String, status: String): ProcessInspection.Present {
    val open = stat.indexOf('(')
    val close = stat.lastIndexOf(')')
    require(open > 0 && close > open)
    val pid = stat.substring(0, open).trim().toInt()
    val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
    require(fields.size >= 20 && fields[0].length == 1)
    val parent = fields[1].toInt()
    val ticks = fields[19].toLong()
    val uidFields = status.lineSequence().single { it.startsWith("Uid:") }
        .substringAfter(':').trim().split(Regex("\\s+"))
    require(uidFields.size == 4)
    val uids = uidFields.map { it.toInt() }
    require(pid > 1 && parent > 0 && ticks >= 0 && uids.all { it >= 0 })
    // Reject unusual credential transitions rather than granting signal ownership.
    require(uids.distinct().size == 1)
    val caught = status.lineSequence().singleOrNull { it.startsWith("SigCgt:") }
        ?.substringAfter(':')?.trim()?.let { java.lang.Long.parseUnsignedLong(it, 16) } ?: 0L
    return ProcessInspection.Present(OwnedProcessIdentity(pid, ticks, uids.first()), parent, fields[0][0],
        quitHandlerInstalled = caught and (1L shl 2) != 0L)
}

/** Errors/denied inspection are uncertainty, never permission to signal a guessed PID. */
internal fun inspectProcIdentity(pid: Int, root: File = File("/proc")): ProcessInspection = try {
    require(pid > 1)
    val dir = File(root, pid.toString())
    val before = File(dir, "stat").readText()
    val status = File(dir, "status").readText()
    val first = parseProcIdentity(before, status)
    val after = parseProcIdentity(File(dir, "stat").readText(), status)
    if (first.identity != after.identity || first.identity.pid != pid) ProcessInspection.Unknown else after
} catch (_: Exception) {
    ProcessInspection.Unknown
}

/** Only the expected executable with an installed QUIT handler may receive cleanup QUIT. */
internal fun hasQuitCleanupHandler(identity: OwnedProcessIdentity, executable: String): Boolean = try {
    val current = inspectProcIdentity(identity.pid) as? ProcessInspection.Present
    if (current?.identity != identity || !current.quitHandlerInstalled) false else {
        val actual = Files.readSymbolicLink(File("/proc/${identity.pid}/exe").toPath()).toFile().canonicalPath
        actual == File(executable).canonicalPath &&
            (inspectProcIdentity(identity.pid) as? ProcessInspection.Present)?.let {
                it.identity == identity && it.quitHandlerInstalled
            } == true
    }
} catch (_: Exception) { false }

/** Independent of service cancellation, with bounded thread admission. No pipe closing here. */
internal object RuntimeSignalWorkers {
    private val permits = Semaphore(4)
    fun schedule(action: () -> Unit): Boolean {
        if (!permits.tryAcquire()) return false
        try {
            Thread({ try { action() } finally { permits.release() } }, "seed-runtime-signal")
                .apply { isDaemon = true }.start()
        } catch (failure: Exception) {
            permits.release()
            throw failure
        }
        return true
    }
}

/** Keep Java's direct-child streams/reaping; replace only the incorrect TERM/KILL policy. */
internal class OwnedRuntimeProcess(
    private val process: Process,
    private val identity: OwnedProcessIdentity,
    private val inspect: (Int) -> ProcessInspection,
    private val signal: (Int, OwnedProcessSignal) -> Unit,
    private val schedule: (() -> Unit) -> Boolean = RuntimeSignalWorkers::schedule,
    private val onFailure: (Throwable) -> Unit = {},
    private val cleanupReady: () -> Boolean = { true },
    private val readinessTimeoutMs: Long = 5_000,
) : Process() {
    private val inFlight = AtomicBoolean(false)
    private val dispatched = AtomicBoolean(false)

    override fun destroy() {
        if (dispatched.get() || !inFlight.compareAndSet(false, true)) return
        // Another worker may have completed between the first check and admission.
        if (dispatched.get()) {
            inFlight.set(false)
            return
        }
        try {
            if (!schedule {
                try {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readinessTimeoutMs)
                    while (!cleanupReady()) {
                        if (!process.isAlive) return@schedule
                        check(System.nanoTime() < deadline) { "Runtime cleanup handler is not ready" }
                        check((inspect(identity.pid) as? ProcessInspection.Present)?.identity == identity) {
                            "Could not verify runtime identity while awaiting cleanup readiness"
                        }
                        Thread.sleep(20)
                    }
                    if (!process.isAlive) return@schedule
                    when (val current = inspect(identity.pid)) {
                        is ProcessInspection.Present -> {
                            check(current.identity == identity && current.state != 'Z') { "Runtime identity changed" }
                        }
                        ProcessInspection.Gone -> return@schedule
                        ProcessInspection.Unknown -> error("Could not verify runtime identity")
                    }
                    signal(identity.pid, OwnedProcessSignal.QUIT)
                    // QUIT may already have exited the process; never signal a reused PID.
                    if (process.isAlive) {
                        val current = inspect(identity.pid)
                        check(current is ProcessInspection.Present && current.identity == identity && current.state != 'Z') {
                            "Could not verify runtime identity before continuing"
                        }
                        signal(identity.pid, OwnedProcessSignal.CONTINUE)
                    }
                    dispatched.set(true)
                } catch (failure: Exception) { onFailure(failure) }
                finally { inFlight.set(false) }
            }) {
                inFlight.set(false)
                onFailure(IllegalStateException("Runtime signal workers are busy"))
            }
        } catch (failure: Exception) {
            inFlight.set(false)
            onFailure(failure)
        }
    }

    // Tracer-only KILL bypasses guest cleanup. Unconfirmed exit must fail the supervisor gate.
    override fun destroyForcibly(): Process { destroy(); return this }
    override fun isAlive(): Boolean = process.isAlive
    override fun waitFor(): Int = process.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
    override fun exitValue(): Int = process.exitValue()
    override fun getInputStream() = process.inputStream
    override fun getErrorStream() = process.errorStream
    override fun getOutputStream() = process.outputStream
}

internal class OwnedRuntimeProcessFactory(
    private val receiptRoot: File,
    private val parentPid: Int,
    private val uid: Int,
    private val inspect: (Int) -> ProcessInspection,
    private val signal: (Int, OwnedProcessSignal) -> Unit,
    private val factory: ProcessFactory = JvmProcessFactory,
    private val timeoutMs: Long = 10_000,
    private val shell: String = "/system/bin/sh",
    private val script: String = HANDSHAKE_SCRIPT,
    private val onFailure: (Throwable) -> Unit = {},
    private val cleanupReady: (OwnedProcessIdentity, String) -> Boolean = { _, _ -> true },
    private val cleanupSchedule: (() -> Unit) -> Boolean = RuntimeSignalWorkers::schedule,
    private val cleanupReadinessTimeoutMs: Long = 5_000,
) : ProcessFactory {
    // Keep even superseded launches owned until Java observes their exit.
    private var previousLaunch: Process? = null

    @Synchronized
    override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
        previousLaunch?.takeIf { it.isAlive }?.let { previous ->
            // Failed startup returns no handle to the supervisor. Retry must still
            // be able to re-attempt its cleanup, never bypass its ownership gate.
            if (previous is OwnedRuntimeProcess) previous.destroy() else {
                if (!cleanupSchedule { runCatching { previous.destroy() }.onFailure(onFailure) }) {
                    onFailure(IllegalStateException("Runtime cleanup workers are busy"))
                }
            }
            error("A previous runtime launch has not exited; cleanup was requested again")
        }
        previousLaunch = null
        require(command.isNotEmpty() && timeoutMs > 0 && parentPid > 1 && uid >= 0)
        check(receiptRoot.isDirectory || receiptRoot.mkdirs()) { "Could not create runtime ownership directory" }
        val dir = Files.createTempDirectory(receiptRoot.toPath(), "launch-").toFile()
        val nonce = UUID.randomUUID().toString()
        var process: Process? = null
        var ownedIdentity: OwnedProcessIdentity? = null
        var acknowledged = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            process = factory.start(listOf(shell, "-c", script, "seed-runtime-launch", dir.absolutePath, nonce) + command,
                workingDir, environment)
            previousLaunch = process
            val receipt = File(dir, "receipt")
            waitForFile(receipt, process, deadline)
            val lines = readBounded(receipt).lines()
            check(lines.size == 3 && lines[0] == nonce && lines[2].isEmpty()) { "Invalid runtime launch receipt" }
            val claimed = parseProcIdentity(lines[1], "Uid:\t$uid\t$uid\t$uid\t$uid\n")
            val actual = inspect(claimed.identity.pid)
            check(actual is ProcessInspection.Present && actual.identity == claimed.identity &&
                actual.parentPid == parentPid && claimed.parentPid == parentPid && actual.state != 'Z') {
                "Could not establish runtime process ownership"
            }
            ownedIdentity = claimed.identity
            // Once acknowledged, the wrapper may exec only the fixed separately supplied argv.
            File(dir, "ack.tmp").writeText("$nonce\n")
            check(File(dir, "ack.tmp").renameTo(File(dir, "ack"))) { "Could not acknowledge runtime launch" }
            acknowledged = true
            val accepted = File(dir, "accepted")
            waitForFile(accepted, process, deadline)
            check(readBounded(accepted) == "$nonce\n") { "Invalid runtime launch acknowledgment" }
            val owned = OwnedRuntimeProcess(process, claimed.identity, inspect, signal, onFailure = onFailure,
                cleanupReady = { cleanupReady(claimed.identity, command.first()) },
                schedule = cleanupSchedule, readinessTimeoutMs = cleanupReadinessTimeoutMs)
            previousLaunch = owned
            return owned
        } catch (failure: Exception) {
            // Without an acknowledgment the fixed wrapper cannot exec PRoot and self-expires.
            // If acknowledgment was sent, use verified ownership, never Java's blind TERM fallback.
            val raw = process
            if (raw != null) {
                val established = ownedIdentity
                if (acknowledged && established != null) {
                    val failed = OwnedRuntimeProcess(raw, established, inspect, signal, onFailure = onFailure,
                        cleanupReady = { cleanupReady(established, command.first()) },
                        schedule = cleanupSchedule, readinessTimeoutMs = cleanupReadinessTimeoutMs)
                    previousLaunch = failed
                    failed.destroy()
                } else {
                    // Only the unacknowledged host shell, not a PRoot generation.
                    if (!cleanupSchedule { runCatching { raw.destroy() }.onFailure(onFailure) }) {
                        onFailure(IllegalStateException("Runtime cleanup workers are busy"))
                    }
                }
            }
            throw IllegalStateException("Could not launch owned runtime process", failure)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun waitForFile(file: File, process: Process, deadline: Long) {
        while (!file.exists()) {
            check(process.isAlive) { "Runtime launch wrapper exited" }
            check(System.nanoTime() < deadline) { "Runtime launch handshake timed out" }
            Thread.sleep(10)
        }
    }

    private fun readBounded(file: File): String {
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..4096) {
            "Invalid runtime ownership file"
        }
        return file.inputStream().use { input ->
            val bytes = ByteArray(4097)
            var total = 0
            while (total < bytes.size) {
                val count = input.read(bytes, total, bytes.size - total)
                if (count < 0) break
                total += count
            }
            check(total <= 4096) { "Runtime ownership file too large" }
            bytes.copyOf(total).toString(Charsets.UTF_8)
        }
    }

    companion object {
        // All paths/argv arrive as positional parameters; no guest-controlled shell expansion.
        val HANDSHAKE_SCRIPT = """
            dir=${'$'}1
            nonce=${'$'}2
            shift 2
            umask 077
            stat=${'$'}(/system/bin/cat /proc/${'$'}${'$'}/stat) || exit 125
            printf '%s\n%s\n' "${'$'}nonce" "${'$'}stat" > "${'$'}dir/receipt.tmp" || exit 125
            /system/bin/mv "${'$'}dir/receipt.tmp" "${'$'}dir/receipt" || exit 125
            n=0
            while [ "${'$'}n" -lt 100 ]; do
                if [ -f "${'$'}dir/ack" ]; then
                    IFS= read -r ack < "${'$'}dir/ack" || exit 125
                    [ "${'$'}ack" = "${'$'}nonce" ] || exit 125
                    printf '%s\n' "${'$'}nonce" > "${'$'}dir/accepted.tmp" || exit 125
                    /system/bin/mv "${'$'}dir/accepted.tmp" "${'$'}dir/accepted" || exit 125
                    exec "${'$'}@"
                    exit 126
                fi
                n=${'$'}((n + 1))
                /system/bin/sleep 0.1 || exit 125
            done
            exit 124
        """.trimIndent()
    }
}
