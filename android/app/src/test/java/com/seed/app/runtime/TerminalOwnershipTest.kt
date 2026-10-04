package com.seed.app.runtime

import org.junit.Assert.*
import org.junit.Test

class TerminalOwnershipTest {
    private val stat = "42 (shell) S 7 " + List(17) { "0" }.joinToString(" ") + " 123"
    private val actual = parseProcIdentity(stat, "Uid: 9 9 9 9\n")

    @Test fun boundsReceiptAndRejectsSymlinks() {
        val root = java.nio.file.Files.createTempDirectory("terminal-test").toFile()
        try {
            val receipt = java.io.File(root, "receipt")
            receipt.writeText("x".repeat(4097))
            assertThrows(IllegalStateException::class.java) { readTerminalOwnershipFile(receipt) }
            receipt.writeText("ok\n")
            val link = java.io.File(root, "link")
            java.nio.file.Files.createSymbolicLink(link.toPath(), receipt.toPath())
            assertThrows(IllegalStateException::class.java) { readTerminalOwnershipFile(link) }
            assertEquals("ok\n", readTerminalOwnershipFile(receipt))
        } finally { root.deleteRecursively() }
    }

    @Test fun failedAcceptanceAfterAckNeverUsesRawKill() {
        val root = java.nio.file.Files.createTempDirectory("terminal-test").toFile()
        try {
            val signals = mutableListOf<OwnedProcessSignal>()
            val failures = mutableListOf<Throwable>()
            val raw = FakeProcess()
            val owner = TerminalOwnership(root, "/fixed/proot", 7, 9, { actual },
                { _, signal -> signals.add(signal) }, failures::add,
                cleanupReady = { _, _ -> true }, schedule = { it(); true })
            val command = listOf("/fixed/proot", "literal ; argv", "/usr/bin/python3", "-u", "/fixed/repl.py")
            assertEquals(command, owner.argv(command).toList().drop(6))
            java.io.File(owner.directory, "receipt").writeText("${owner.nonce}\n$stat\n")
            java.io.File(owner.directory, "accepted").writeText("wrong\n")
            owner.establish(raw, 42)
            assertTrue(failures.isNotEmpty())
            assertEquals(0, raw.kills)
            assertEquals(listOf(OwnedProcessSignal.QUIT, OwnedProcessSignal.CONTINUE), signals)
            owner.destroyOwned()
            assertEquals(0, raw.kills)
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidReceiptOnlyKillsUnacknowledgedShell() {
        val root = java.nio.file.Files.createTempDirectory("terminal-test").toFile()
        try {
            val raw = FakeProcess()
            val owner = TerminalOwnership(root, "/fixed/proot", 7, 9, { actual },
                { _, _ -> fail("must not signal unverified identity") }, {},
                schedule = { it(); true })
            java.io.File(owner.directory, "receipt").writeText("bad\n")
            owner.establish(raw, 42)
            assertEquals(1, raw.kills)
        } finally { root.deleteRecursively() }
    }

    private class FakeProcess : Process() {
        var kills = 0
        override fun isAlive() = true
        override fun destroy() { kills++ }
        override fun waitFor() = error("unused")
        override fun exitValue() = error("unused")
        override fun getInputStream(): java.io.InputStream = error("unused")
        override fun getErrorStream(): java.io.InputStream = error("unused")
        override fun getOutputStream(): java.io.OutputStream = error("unused")
    }

    @Test fun acceptsOnlyDirectPtyChild() {
        assertEquals(actual.identity, verifyTerminalReceipt("nonce\n$stat\n", "nonce", 42, 7, 9) { actual })
    }
    @Test fun rejectsReceiptForDifferentPtyPid() {
        assertThrows(IllegalStateException::class.java) {
            verifyTerminalReceipt("nonce\n$stat\n", "nonce", 43, 7, 9) { actual }
        }
    }
    @Test fun rejectsReusedPidAndWrongParentAndNonce() {
        for (inspection in listOf(actual.copy(identity = actual.identity.copy(startTicks = 124)),
            actual.copy(parentPid = 8), ProcessInspection.Unknown)) {
            assertThrows(IllegalStateException::class.java) {
                verifyTerminalReceipt("nonce\n$stat\n", "nonce", 42, 7, 9) { inspection }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            verifyTerminalReceipt("wrong\n$stat\n", "nonce", 42, 7, 9) { actual }
        }
    }
}
