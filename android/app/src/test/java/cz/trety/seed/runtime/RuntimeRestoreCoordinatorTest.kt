package cz.trety.seed.runtime

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeRestoreCoordinatorTest {
    private val version = RootfsVersion("test", "trusted")
    private val committedJournal get() = """{"phase":"COMMITTED","sourceRootfsVersion":${version.toMarkerJson()}}"""
    private val unusedSource = object : AssetSource {
        override fun entries(): List<AssetEntry> = error("must not extract")
        override fun open(name: String): java.io.InputStream = error("must not open")
    }
    @Test fun failedStopLeavesRootAndMetadataUntouchedAndDoesNotResume() = runBlocking {
        val linux = Files.createTempDirectory("restore-stop-").toFile()
        try {
            File(linux, "rootfs").mkdir()
            File(linux, "rootfs/old").writeText("old")
            File(linux, ".version").writeText("old-marker")
            var resumed = false
            val coordinator = RuntimeRestoreCoordinator(linux, unusedSource, version, {}, { false }, { resumed = true })
            coordinator.restore()
            assertTrue(coordinator.state.value is RestoreState.Failed)
            assertEquals("old", File(linux, "rootfs/old").readText())
            assertEquals("old-marker", File(linux, ".version").readText())
            assertFalse(resumed)
            assertFalse(File(linux, ".restore-journal").exists())
        } finally { linux.deleteRecursively() }
    }
    @Test fun concurrentRestoreIsIgnoredRatherThanQueuedForReplay() = runBlocking {
        val linux = Files.createTempDirectory("restore-duplicate-").toFile()
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var stopped = 0
            val coordinator = RuntimeRestoreCoordinator(linux, unusedSource, version, {}, {
                stopped++
                entered.complete(Unit)
                release.await()
                false
            }, {})
            val first = launch { coordinator.restore() }
            entered.await()
            coordinator.restore()
            assertEquals(1, stopped)
            release.complete(Unit)
            first.join()
            assertEquals(1, stopped)
        } finally { linux.deleteRecursively() }
    }
    @Test fun committedRecoveryPublishesTrustedMetadataBeforeWriterAndJournalRemoval() {
        val linux = Files.createTempDirectory("restore-metadata-").toFile()
        try {
            File(linux, "rootfs").mkdir()
            File(linux, ".restore-old").mkdir()
            File(linux, ".restore-journal").writeText(committedJournal)
            File(linux, ".version").writeText("untrusted")
            RuntimeMaintenanceGate.withWriter {
                recoverRuntimeRestore(linux)
                assertEquals(version, RootfsVersion.parse(File(linux, ".version").readText()))
                assertEquals(version, RootfsVersion.parse(File(linux, "seed_version.json").readText()))
                assertFalse(File(linux, ".restore-journal").exists())
                assertFalse(File(linux, ".restore-old").exists())
            }
        } finally { linux.deleteRecursively() }
    }
    @Test fun metadataPublicationFailureRetainsCommittedJournalForStartupRetry() {
        val linux = Files.createTempDirectory("restore-metadata-failure-").toFile()
        try {
            File(linux, "rootfs").mkdir()
            File(linux, ".restore-old").mkdir()
            File(linux, ".restore-journal").writeText(committedJournal)
            assertThrows(java.io.IOException::class.java) {
                RuntimeRestoreTransaction(linux, publishCommittedMetadata = { throw java.io.IOException("disk") }).recover()
            }
            assertEquals(committedJournal, File(linux, ".restore-journal").readText())
            assertTrue(File(linux, ".restore-old").exists())
            recoverRuntimeRestore(linux)
            assertFalse(File(linux, ".restore-journal").exists())
            assertEquals(version, RootfsVersion.parse(File(linux, ".version").readText()))
        } finally { linux.deleteRecursively() }
    }
    @Test fun missingPackagedArchiveFailsBeforeExtraction() {
        val source = object : AssetSource {
            override fun entries() = listOf(AssetEntry("seed_version.json", 1))
            override fun open(name: String): java.io.InputStream = error("not opened")
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { requireRestoreAssets(source) }
    }
}
