package cz.trety.seed.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeRestoreTransactionTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("restore-test-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun tree(dir: File) {
        File(dir, "rootfs/home/seed/app/.git").mkdirs()
        File(dir, "rootfs/home/seed/app/.git/config").writeText("git")
        File(dir, "rootfs/home/seed/app/data.db-wal").writeText("wal")
        File(dir, "rootfs/home/seed/backend").mkdirs()
        File(dir, "rootfs/home/seed/backend/config.json").writeText("credentials")
        File(dir, "rootfs/broken").writeText("discard")
        File(dir, "pi-agent/sessions").mkdirs()
        File(dir, "pi-agent/sessions/session").writeText("session")
    }
    private suspend fun fresh(stage: File) {
        File(stage, "rootfs/home/seed/app").mkdirs()
        File(stage, "rootfs/home/seed/backend").mkdirs()
        File(stage, "rootfs/fresh").writeText("new")
    }
    @Test fun committedRecoveryUsesRecordedVersionNotUpdatedApk() = fixture { linux -> runBlocking {
        tree(linux)
        val a = RootfsVersion("a", "a")
        val b = RootfsVersion("b", "b")
        try {
            RuntimeRestoreTransaction(linux, sourceRootfsVersion = a, checkpoint = {
                if (it == RuntimeRestoreTransaction.Checkpoint.COMMITTED) throw AssertionError("death")
            }).restore({ true }, ::fresh)
        } catch (_: AssertionError) { }
        RuntimeRestoreTransaction(linux, sourceRootfsVersion = b,
            publishCommittedMetadata = { publishRuntimeMetadata(linux, it) }).recover()
        assertEquals(a, RootfsVersion.parse(File(linux, ".version").readText()))
        assertNotEquals(b, RootfsVersion.parse(File(linux, ".version").readText()))
        var destructiveExtraction = false
        val source = object : AssetSource {
            override fun entries() = emptyList<AssetEntry>()
            override fun open(name: String): java.io.InputStream = error("unused")
        }
        val boot = BootController(linux, source, b, scope = this, extractionFlow = {
            destructiveExtraction = true
            kotlinx.coroutines.flow.flowOf(ExtractionProgress.Finished)
        })
        assertEquals(BootState.NeedsExtraction, boot.states.value)
        boot.runExtraction()
        kotlinx.coroutines.yield()
        assertFalse(destructiveExtraction)
        assertEquals(BootState.Failed, boot.states.value)
        assertEquals("wal", File(linux, "rootfs/home/seed/app/data.db-wal").readText())
    } }
    @Test fun restoresMissingRootfsWithoutTouchingHostPiData() = fixture { linux -> runBlocking {
        File(linux, "pi-agent").mkdirs()
        File(linux, "pi-agent/auth.json").writeText("preserve")
        RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test"))
            .restore({ true }, ::fresh)
        assertTrue(File(linux, "rootfs/fresh").exists())
        assertEquals("preserve", File(linux, "pi-agent/auth.json").readText())
        assertFalse(File(linux, ".restore-old").exists())
    } }
    @Test fun preservesAppAndConfigButDiscardsBrokenRuntime() = fixture { linux -> runBlocking {
        tree(linux)
        RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test")).restore(confirmedStopped = { true }, extract = ::fresh)
        assertEquals("wal", File(linux, "rootfs/home/seed/app/data.db-wal").readText())
        assertEquals("git", File(linux, "rootfs/home/seed/app/.git/config").readText())
        assertEquals("credentials", File(linux, "rootfs/home/seed/backend/config.json").readText())
        assertEquals("session", File(linux, "pi-agent/sessions/session").readText())
        assertFalse(File(linux, "rootfs/broken").exists())
        assertTrue(File(linux, "rootfs/fresh").exists())
        assertFalse(File(linux, ".restore-old").exists())
        assertFalse(File(linux, ".restore-journal").exists())
    } }
    @Test fun failedStopDoesNotExtractOrMutate() = fixture { linux -> runBlocking {
        tree(linux)
        var extracted = false
        try {
            RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test")).restore({ false }) { extracted = true }
            fail("must refuse unconfirmed exit")
        } catch (_: IllegalStateException) { }
        assertFalse(extracted)
        assertEquals("discard", File(linux, "rootfs/broken").readText())
    } }
    @Test fun interruptionBeforeCommitRollsBackAndAfterCommitFinishes() = fixture { linux -> runBlocking {
        for (point in RuntimeRestoreTransaction.Checkpoint.values()) {
            linux.deleteRecursively(); linux.mkdirs(); tree(linux)
            try {
                RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test"), checkpoint = { if (it == point) throw AssertionError("power loss") })
                    .restore({ true }, ::fresh)
                fail("checkpoint not reached")
            } catch (_: AssertionError) { }
            RuntimeRestoreTransaction(linux).recover()
            val committed = point == RuntimeRestoreTransaction.Checkpoint.COMMITTED
            assertEquals(!committed, File(linux, "rootfs/broken").exists())
            assertEquals(committed, File(linux, "rootfs/fresh").exists())
            assertEquals("wal", File(linux, "rootfs/home/seed/app/data.db-wal").readText())
            assertFalse(File(linux, ".restore-old").exists())
        }
    } }
    @Test fun refusesSymlinkAncestorWithoutTouchingTarget() = fixture { linux -> runBlocking {
        val outside = Files.createTempDirectory("restore-outside-").toFile()
        try {
            tree(linux)
            File(linux, "rootfs/home/seed/app").deleteRecursively()
            Files.createSymbolicLink(File(linux, "rootfs/home/seed/app").toPath(), outside.toPath())
            try { RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test")).restore({ true }, ::fresh); fail("symlink") }
            catch (_: java.io.IOException) { }
            assertTrue(File(linux, "rootfs/broken").exists())
        } finally { outside.deleteRecursively() }
    } }
    @Test fun legacyPiFailsClosedRatherThanDeletingSessions() = fixture { linux -> runBlocking {
        tree(linux)
        File(linux, "rootfs/home/seed/.pi/agent/sessions").mkdirs()
        File(linux, "rootfs/home/seed/.pi/agent/sessions/legacy").writeText("legacy")
        try { RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test")).restore({ true }, ::fresh); fail("legacy Pi") }
        catch (_: java.io.IOException) { }
        assertEquals("legacy", File(linux, "rootfs/home/seed/.pi/agent/sessions/legacy").readText())
        assertEquals("discard", File(linux, "rootfs/broken").readText())
    } }
    @Test fun outsideStorageIsUntouchedAndAppSymlinksAreCopiedWithoutFollowing() = fixture { files -> runBlocking {
        val linux = File(files, "linux").apply { mkdirs() }
        tree(linux)
        for (name in listOf("datastore/settings", "shared_prefs/consent", "app_webview/Local Storage/data")) {
            File(files, name).apply { parentFile!!.mkdirs(); writeText(name) }
        }
        val external = File(files, "external").apply { writeText("outside") }
        val link = File(linux, "rootfs/home/seed/app/external-link").toPath()
        Files.createSymbolicLink(link, external.toPath())
        RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test")).restore({ true }, ::fresh)
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(external.toPath(), Files.readSymbolicLink(link))
        assertEquals("outside", external.readText())
        for (name in listOf("datastore/settings", "shared_prefs/consent", "app_webview/Local Storage/data")) {
            assertEquals(name, File(files, name).readText())
        }
    } }
    @Test fun failedExtractionLeavesOriginalDataAndRemovesOnlyStage() = fixture { linux -> runBlocking {
        tree(linux)
        try {
            RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test")).restore({ true }) { stage ->
                File(stage, "rootfs").mkdirs()
                File(stage, "rootfs/partial").writeText("partial")
                throw java.io.IOException("invalid archive")
            }
            fail("extraction must fail")
        } catch (_: java.io.IOException) { }
        assertEquals("wal", File(linux, "rootfs/home/seed/app/data.db-wal").readText())
        assertTrue(File(linux, "rootfs/broken").exists())
        assertFalse(File(linux, ".restore-stage").exists())
        assertFalse(File(linux, ".restore-journal").exists())
    } }
    @Test fun interruptedJournalPublicationAndRollbackCleanupAreRecoverable() = fixture { linux ->
        tree(linux)
        fun journal(phase: String) = """{"phase":"$phase","sourceRootfsVersion":${RootfsVersion("test", "test").toMarkerJson()}}"""
        File(linux, ".restore-journal.tmp").writeText(journal("EXTRACTING"))
        RuntimeRestoreTransaction(linux).recover()
        assertFalse(File(linux, ".restore-journal.tmp").exists())
        File(linux, ".restore-journal").writeText(journal("PREPARED"))
        File(linux, ".restore-journal.tmp").writeText(journal("COMMITTED"))
        File(linux, ".restore-stage/rootfs").mkdirs()
        assertTrue(File(linux, "rootfs").renameTo(File(linux, ".restore-old")))
        File(linux, "rootfs").mkdir()
        RuntimeRestoreTransaction(linux).recover()
        assertEquals("discard", File(linux, "rootfs/broken").readText())
        File(linux, ".restore-journal").writeText(journal("ROLLING_BACK"))
        File(linux, ".restore-stage").mkdir()
        RuntimeRestoreTransaction(linux).recover()
        assertEquals("discard", File(linux, "rootfs/broken").readText())
    }
    @Test fun journalRejectsMalformedDuplicateOversizedAndSymlinkPayloads() = fixture { linux ->
        tree(linux)
        val marker = RootfsVersion("test", "test").toMarkerJson()
        val valid = """{"phase":"COMMITTED","sourceRootfsVersion":$marker}"""
        for (payload in listOf(valid + "garbage", valid.replace("\"phase\":", "\"phase\":\"COMMITTED\",\"phase\":"),
            valid.replace("\"test\"", "\"bad value\""), "x".repeat(2049))) {
            File(linux, ".restore-journal").writeText(payload)
            assertThrows(java.io.IOException::class.java) { RuntimeRestoreTransaction(linux).recover() }
            assertTrue(File(linux, "rootfs/broken").exists())
        }
        File(linux, ".restore-journal").delete()
        val outside = File(linux, "outside").apply { writeText(valid) }
        Files.createSymbolicLink(File(linux, ".restore-journal").toPath(), outside.toPath())
        assertThrows(java.io.IOException::class.java) { RuntimeRestoreTransaction(linux).recover() }
        assertEquals(valid, outside.readText())
    }
    @Test fun unknownJournalOrUnownedArtifactsFailClosed() = fixture { linux ->
        tree(linux)
        File(linux, ".restore-journal").writeText("UNKNOWN")
        try { RuntimeRestoreTransaction(linux).recover(); fail("unknown journal") }
        catch (_: java.io.IOException) { }
        assertTrue(File(linux, "rootfs/broken").exists())
        File(linux, ".restore-journal").delete()
        File(linux, ".restore-old").mkdir()
        try { RuntimeRestoreTransaction(linux).recover(); fail("unowned old tree") }
        catch (_: java.io.IOException) { }
        assertTrue(File(linux, "rootfs/broken").exists())
    }
    @Test fun lowSpaceAndCancellationLeaveOldTreeIntact() = fixture { linux -> runBlocking {
        tree(linux)
        try { RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test"), availableBytes = { 0 }).restore({ true }, ::fresh); fail("space") }
        catch (_: java.io.IOException) { }
        try {
            RuntimeRestoreTransaction(linux, sourceRootfsVersion = RootfsVersion("test", "test")).restore({ true }) { throw kotlinx.coroutines.CancellationException("cancel") }
            fail("cancel")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("discard", File(linux, "rootfs/broken").readText())
        assertFalse(File(linux, ".restore-stage").exists())
    } }
}
