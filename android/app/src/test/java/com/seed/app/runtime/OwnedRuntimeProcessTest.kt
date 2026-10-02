package com.seed.app.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class OwnedRuntimeProcessTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun stat(pid: Int = 42, parent: Int = 7, ticks: Long = 99): String =
        "$pid (name with ) parentheses) S $parent " + List(17) { "0" }.joinToString(" ") + " $ticks"
    private val identity = OwnedProcessIdentity(42, 99, 1000)
    private fun matching() = ProcessInspection.Present(identity, parentPid = 7, state = 'S')

    @Test fun procStatParserHandlesNamesContainingParentheses() {
        val parsed = parseProcIdentity(stat(), "Name:\tx\nUid:\t1000\t1000\t1000\t1000\n")
        assertEquals(matching(), parsed)
    }

    @Test fun malformedProcIdentityIsRejected() {
        for (text in listOf("", "42 (x) S", stat(pid = 0), stat(ticks = -1))) {
            assertThrows(IllegalArgumentException::class.java) {
                parseProcIdentity(text, "Uid:\t1000\t1000\t1000\t1000\n")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { parseProcIdentity(stat(), "Uid: invalid") }
    }

    @Test fun destroySchedulesQuitThenContinueOnceWithoutJavaDestroy() {
        val raw = FakeOwnedProcess()
        val queued = mutableListOf<() -> Unit>()
        val signals = mutableListOf<OwnedProcessSignal>()
        val owned = OwnedRuntimeProcess(raw, identity, { matching() }, { _, signal -> signals += signal },
            schedule = { queued += it; true })
        owned.destroy()
        owned.destroyForcibly()
        assertTrue(signals.isEmpty())
        assertEquals(1, queued.size)
        queued.single()()
        assertEquals(listOf(OwnedProcessSignal.QUIT, OwnedProcessSignal.CONTINUE), signals)
        assertEquals(0, raw.destroyCalls)
        assertTrue(owned.isAlive)
    }

    @Test fun reusedPidOrUnknownIdentityIsNeverSignalled() {
        for (inspection in listOf(ProcessInspection.Unknown,
            ProcessInspection.Present(identity.copy(startTicks = 100), 7, 'S'), ProcessInspection.Gone)) {
            var signals = 0
            val owned = OwnedRuntimeProcess(FakeOwnedProcess(), identity, { inspection }, { _, _ -> signals++ },
                schedule = { it(); true })
            owned.destroy()
            assertEquals(0, signals)
        }
    }

    @Test fun identityIsRecheckedBeforeContinue() {
        var reads = 0
        val signals = mutableListOf<OwnedProcessSignal>()
        val owned = OwnedRuntimeProcess(FakeOwnedProcess(), identity,
            { if (++reads == 1) matching() else ProcessInspection.Unknown },
            { _, signal -> signals += signal }, schedule = { it(); true })
        owned.destroy()
        assertEquals(listOf(OwnedProcessSignal.QUIT), signals)
    }

    @Test fun signalFailureIsReportedAndDoesNotKillBlindly() {
        var failure: Throwable? = null
        val owned = OwnedRuntimeProcess(FakeOwnedProcess(), identity, { matching() },
            { _, _ -> error("denied") }, schedule = { it(); true }, onFailure = { failure = it })
        owned.destroy()
        assertNotNull(failure)
        assertTrue(owned.isAlive)
    }

    @Test fun handshakeValidatesOwnershipBeforeAcknowledgmentAndKeepsArgvSeparate() {
        val root = temporary.newFolder()
        var launchDir: File? = null
        var captured: List<String>? = null
        val factory = object : ProcessFactory {
            override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
                captured = command
                val dir = File(command[4]); launchDir = dir
                File(dir, "receipt").writeText(command[5] + "\n" + stat() + "\n")
                return FakeOwnedProcess { File(dir, "ack").takeIf { it.isFile }?.let {
                    File(dir, "accepted").writeText(it.readText())
                } }
            }
        }
        val ownedFactory = OwnedRuntimeProcessFactory(root, parentPid = 7, uid = 1000,
            inspect = { matching() }, signal = { _, _ -> }, factory = factory)
        val argv = listOf("/proot", "argument with spaces", "$(not-a-command)")
        ownedFactory.start(argv, root, emptyMap())
        assertEquals(argv, captured!!.drop(6))
        assertFalse(launchDir!!.exists())
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(argv, root, emptyMap()) }
    }

    @Test fun wrongNonceOrParentCannotAuthorizeLaunch() {
        for (wrongNonce in listOf(true, false)) {
            val root = temporary.newFolder()
            var sawAck = false
            val factory = object : ProcessFactory {
                override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
                    val dir = File(command[4])
                    File(dir, "receipt").writeText((if (wrongNonce) "wrong" else command[5]) + "\n" + stat() + "\n")
                    return FakeOwnedProcess { sawAck = File(dir, "ack").exists() }
                }
            }
            val ownedFactory = OwnedRuntimeProcessFactory(root, parentPid = 8, uid = 1000,
                inspect = { matching() }, signal = { _, _ -> }, factory = factory, timeoutMs = 50)
            assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
            assertFalse(sawAck)
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun realHostHandshakeExecsLiteralArgvAndReapsDirectChild() {
        val self = parseProcIdentity(File("/proc/self/stat").readText(), File("/proc/self/status").readText())
        val root = temporary.newFolder()
        val factory = OwnedRuntimeProcessFactory(root, self.identity.pid, self.identity.uid,
            inspect = { inspectProcIdentity(it) }, signal = { _, _ -> error("not needed") },
            shell = "/bin/sh", script = OwnedRuntimeProcessFactory.HANDSHAKE_SCRIPT.replace("/system/bin/", "/usr/bin/"))
        val process = factory.start(listOf("/usr/bin/printf", "%s", "literal ${'$'}(not-a-command)"), root, emptyMap())
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("literal ${'$'}(not-a-command)", process.inputStream.bufferedReader().readText())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun badAcknowledgmentRetainsFailedLaunchAndBlocksAnotherGeneration() {
        val root = temporary.newFolder()
        var launches = 0
        val factory = object : ProcessFactory {
            override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
                launches++
                val dir = File(command[4])
                File(dir, "receipt").writeText(command[5] + "\n" + stat() + "\n")
                return FakeOwnedProcess {
                    if (File(dir, "ack").exists()) File(dir, "accepted").writeText("wrong\n")
                }
            }
        }
        val ownedFactory = OwnedRuntimeProcessFactory(root, 7, 1000, { matching() }, { _, _ -> }, factory = factory)
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
        assertEquals(1, launches)
    }

    @Test fun wrongUidCannotAuthorizeLaunch() {
        val root = temporary.newFolder()
        val factory = object : ProcessFactory {
            override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
                File(command[4], "receipt").writeText(command[5] + "\n" + stat() + "\n")
                return FakeOwnedProcess()
            }
        }
        val ownedFactory = OwnedRuntimeProcessFactory(root, 7, 1000,
            { ProcessInspection.Present(identity.copy(uid = 1001), 7, 'S') }, { _, _ -> }, factory = factory)
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
    }

    @Test fun symlinkReceiptCannotAuthorizeLaunch() {
        val root = temporary.newFolder()
        val external = temporary.newFile()
        val factory = object : ProcessFactory {
            override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
                external.writeText(command[5] + "\n" + stat() + "\n")
                java.nio.file.Files.createSymbolicLink(File(command[4], "receipt").toPath(), external.toPath())
                return FakeOwnedProcess()
            }
        }
        val ownedFactory = OwnedRuntimeProcessFactory(root, 7, 1000, { matching() }, { _, _ -> }, factory = factory)
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
        assertTrue(external.exists())
    }

    @Test fun rejectedCleanupWorkerIsReportedWithoutSynchronousSignals() {
        var failures = 0
        var signals = 0
        val owned = OwnedRuntimeProcess(FakeOwnedProcess(), identity, { matching() }, { _, _ -> signals++ },
            schedule = { false }, onFailure = { failures++ })
        owned.destroy()
        assertEquals(1, failures)
        assertEquals(0, signals)
    }

    @Test fun cleanupWaitsForHandlerReadinessAndCanRetryAfterTimeout() {
        var ready = false
        var failures = 0
        val signals = mutableListOf<OwnedProcessSignal>()
        val owned = OwnedRuntimeProcess(FakeOwnedProcess(), identity, { matching() },
            { _, signal -> signals += signal }, schedule = { it(); true }, onFailure = { failures++ },
            cleanupReady = { ready }, readinessTimeoutMs = 1)
        owned.destroy()
        assertEquals(1, failures)
        assertTrue(signals.isEmpty())
        ready = true
        owned.destroy()
        assertEquals(listOf(OwnedProcessSignal.QUIT, OwnedProcessSignal.CONTINUE), signals)
    }

    @Test fun cleanupCanRetryAfterWorkerAdmissionFailure() {
        var admitted = false
        var failures = 0
        val signals = mutableListOf<OwnedProcessSignal>()
        val owned = OwnedRuntimeProcess(FakeOwnedProcess(), identity, { matching() },
            { _, signal -> signals += signal },
            schedule = { if (admitted) { it(); true } else false }, onFailure = { failures++ })
        owned.destroy()
        assertEquals(1, failures)
        admitted = true
        owned.destroy()
        assertEquals(listOf(OwnedProcessSignal.QUIT, OwnedProcessSignal.CONTINUE), signals)
    }

    @Test fun failedAcknowledgedLaunchCanRetryCleanupWithoutSpawningAgain() {
        val root = temporary.newFolder()
        var ready = false
        var signals = 0
        var launches = 0
        val factory = object : ProcessFactory {
            override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
                launches++
                val dir = File(command[4])
                File(dir, "receipt").writeText(command[5] + "\n" + stat() + "\n")
                return FakeOwnedProcess {
                    if (File(dir, "ack").exists()) File(dir, "accepted").writeText("wrong\n")
                }
            }
        }
        val ownedFactory = OwnedRuntimeProcessFactory(root, 7, 1000, { matching() }, { _, _ -> signals++ },
            factory = factory, cleanupReady = { _, _ -> ready },
            cleanupSchedule = { it(); true }, cleanupReadinessTimeoutMs = 1)
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
        assertEquals(0, signals)
        ready = true
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
        assertEquals(2, signals)
        assertEquals(1, launches)
    }

    @Test fun concurrentDestroyRequestsDispatchOnlyOneCleanup() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val owned = OwnedRuntimeProcess(FakeOwnedProcess(), identity, { matching() }, { _, _ -> calls.incrementAndGet() },
            schedule = { it(); true })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map { pool.submit { repeat(100) { owned.destroy() } } }
            futures.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(2, calls.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun procStatusQuitHandlerBitIsParsedWithoutOtherSignalsCounting() {
        val base = "Uid:\t1000\t1000\t1000\t1000\n"
        assertTrue(parseProcIdentity(stat(), base + "SigCgt:\t0000000000000004\n").quitHandlerInstalled)
        assertFalse(parseProcIdentity(stat(), base + "SigCgt:\t0000000000000002\n").quitHandlerInstalled)
    }

    @Test fun missingReceiptTimesOutWithoutAcknowledgment() {
        val root = temporary.newFolder()
        var launches = 0
        val factory = object : ProcessFactory {
            override fun start(command: List<String>, workingDir: File?, environment: Map<String, String>): Process {
                launches++
                return FakeOwnedProcess()
            }
        }
        val ownedFactory = OwnedRuntimeProcessFactory(root, 7, 1000, { matching() }, { _, _ -> },
            factory = factory, timeoutMs = 30)
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
        assertTrue(root.listFiles()!!.isEmpty())
        assertThrows(IllegalStateException::class.java) { ownedFactory.start(listOf("/proot"), root, emptyMap()) }
        assertEquals(1, launches)
    }
}

private class FakeOwnedProcess(private val tick: () -> Unit = {}) : Process() {
    var destroyCalls = 0
    override fun isAlive(): Boolean { tick(); return true }
    override fun destroy() { destroyCalls++ }
    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
    override fun getOutputStream() = ByteArrayOutputStream()
    override fun waitFor(): Int = error("still alive")
    override fun waitFor(timeout: Long, unit: TimeUnit) = false
    override fun exitValue(): Int = throw IllegalThreadStateException()
}
