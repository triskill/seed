package com.seed.app.runtime

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class RuntimeExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * A trivial two-asset extract produces:
     *   Started(totalBytes=3, fileCount=2)
     *   FileProgress("a", bytesDone=1, totalBytes=3)
     *   FileProgress("b", bytesDone=3, totalBytes=3)
     *   Finished
     * and writes both files to the target. `bytesDone` is the
     * cumulative bytes written (matching the [ExtractionProgress]
     * contract — see [ExtractionProgress.FileProgress]), and
     * `totalBytes` is the same in every event so the UI can render
     * a smooth `bytesDone / totalBytes` progress bar.
     */
    @Test
    fun extractEmitsProgressAndWritesFiles() = runTest {
        val target = tempFolder.newFolder("linux")
        val source = MapAssetSource(
            "a" to "x".toByteArray(),
            "b" to "yz".toByteArray(),
        )
        val extractor = RuntimeExtractor(source)

        val events = extractor.extract(target).toList()

        assertEquals(
            listOf(
                ExtractionProgress.Started(totalBytes = 3, fileCount = 2),
                ExtractionProgress.FileProgress("a", bytesDone = 1, totalBytes = 3),
                ExtractionProgress.FileProgress("b", bytesDone = 3, totalBytes = 3),
                ExtractionProgress.Finished,
            ),
            events,
        )
        assertEquals("x", target.resolve("a").readText())
        assertEquals("yz", target.resolve("b").readText())
    }

    /**
     * Files listed in [AssetSource.entries] with `executable = true`
     * land on disk with the executable bit set. This is how proot
     * becomes runnable inside the extracted runtime.
     */
    @Test
    fun executableFlagIsAppliedOnDisk() = runTest {
        val target = tempFolder.newFolder("linux")
        val source = MapAssetSource(
            "proot" to ByteArray(8) { 0x7f },  // ELF magic-like bytes
            executable = setOf("proot"),
        )
        RuntimeExtractor(source).extract(target).toList()

        val proot = target.resolve("proot")
        assertTrue("proot should be executable", proot.canExecute())
    }

    /**
     * If the source has zero entries, the extractor still emits
     * `Started(0, 0)` and `Finished` and creates the target dir.
     * No files are written.
     */
    @Test
    fun emptySourceEmitsStartedAndFinished() = runTest {
        val target = tempFolder.newFolder("linux")
        val events = RuntimeExtractor(MapAssetSource()).extract(target).toList()
        assertEquals(2, events.size)
        assertTrue(events.first() is ExtractionProgress.Started)
        assertTrue(events.last() is ExtractionProgress.Finished)
    }
}

/** Test-only [AssetSource] backed by an in-memory map. */
private class MapAssetSource(
    vararg pairs: Pair<String, ByteArray>,
    private val executable: Set<String> = emptySet(),
) : AssetSource {
    private val files: Map<String, ByteArray> = pairs.toMap()

    override fun entries(): List<AssetEntry> =
        files.map { (name, bytes) ->
            AssetEntry(name, bytes.size.toLong(), executable.contains(name))
        }

    override fun open(name: String): java.io.InputStream =
        ByteArrayInputStream(files.getValue(name))
}
