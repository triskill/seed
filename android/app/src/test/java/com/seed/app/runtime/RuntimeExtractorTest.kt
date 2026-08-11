package com.seed.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun ordinaryAssetsAreCopiedWithoutExecutableMetadata() = runTest {
        val target = tempFolder.newFolder("linux")
        val source = MapAssetSource("seed_version.json" to "{}".toByteArray())

        RuntimeExtractor(source).extract(target).toList()

        val version = target.resolve("seed_version.json")
        assertEquals("{}", version.readText())
        assertFalse("ordinary asset should not be executable", version.canExecute())
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

    @Test
    fun rootfsTarIsExpandedWithLinksAndExecutableModes() = runTest {
        val target = tempFolder.newFolder("linux")
        val archive = rootfsTar()

        RuntimeExtractor(
            MapAssetSource("rootfs.tar" to archive),
        ).extract(target).toList()

        val rootfs = target.resolve("rootfs")
        val tool = rootfs.resolve("bin/tool")
        val symbolicLink = rootfs.resolve("bin/tool-symlink")
        val hardLink = rootfs.resolve("bin/tool-hardlink")
        assertEquals("runtime-tool", tool.readText())
        assertTrue("archive executable mode should be preserved", tool.canExecute())
        assertTrue(Files.isSymbolicLink(symbolicLink.toPath()))
        assertEquals(Paths.get("tool"), Files.readSymbolicLink(symbolicLink.toPath()))
        assertEquals(tool.readText(), hardLink.readText())
        assertTrue("hard-link copy should preserve executable mode", hardLink.canExecute())
        // Android SELinux denies filesystem hard links in private app data.
        assertFalse("hard-link entry should be an independent file", Files.isSameFile(tool.toPath(), hardLink.toPath()))
        assertFalse("the staging TAR should not remain on disk", target.resolve("rootfs.tar").exists())
    }

    @Test
    fun rootfsTarRejectsEntriesOutsideTheRootfsDirectory() = runTest {
        val target = tempFolder.newFolder("linux")
        val escaped = requireNotNull(target.parentFile).resolve("escaped")
        val archive = tarOf(
            TarFile("../escaped", "owned".toByteArray()),
        )

        val failure = runCatching {
            RuntimeExtractor(
                MapAssetSource("rootfs.tar" to archive),
            ).extract(target).toList()
        }.exceptionOrNull()

        assertNotNull("path traversal must fail extraction", failure)
        assertFalse("path traversal must not write outside rootfs", escaped.exists())
    }

    @Test
    fun nonDirectoryEntryCannotReplaceRootfsDirectory() = runTest {
        val target = tempFolder.newFolder("linux")
        val outsideDir = tempFolder.newFolder("outside")
        val escaped = outsideDir.resolve("escaped")
        val archive = tarOf(
            TarSymbolicLink(".", outsideDir.absolutePath),
            TarFile("escaped", "owned".toByteArray()),
        )

        val failure = runCatching {
            RuntimeExtractor(
                MapAssetSource("rootfs.tar" to archive),
            ).extract(target).toList()
        }.exceptionOrNull()

        assertNotNull("non-directory root entry must fail extraction", failure)
        assertFalse("root replacement must not write outside rootfs", escaped.exists())
    }

    @Test
    fun directoryEntryMayRepresentRootfsDirectory() = runTest {
        val target = tempFolder.newFolder("linux")
        val archive = tarOf(
            TarDirectory("."),
            TarFile("inside", "safe".toByteArray()),
        )

        RuntimeExtractor(
            MapAssetSource("rootfs.tar" to archive),
        ).extract(target).toList()

        assertEquals("safe", target.resolve("rootfs/inside").readText())
    }

    @Test
    fun hardLinkTargetCannotTraverseAnEarlierSymbolicLink() = runTest {
        val target = tempFolder.newFolder("linux")
        val outsideDir = tempFolder.newFolder("outside")
        val victim = outsideDir.resolve("victim").apply { writeText("private") }
        val archive = tarOf(
            TarSymbolicLink("alias", outsideDir.absolutePath),
            TarHardLink("stolen", "alias/victim"),
        )

        val failure = runCatching {
            RuntimeExtractor(
                MapAssetSource("rootfs.tar" to archive),
            ).extract(target).toList()
        }.exceptionOrNull()

        assertNotNull("hard-link traversal must fail extraction", failure)
        assertEquals("private", victim.readText())
        assertFalse(target.resolve("rootfs/stolen").exists())
    }

    @Test
    fun cancellationStopsCopyingBeforeTheNextAssetRead() = runTest {
        val target = tempFolder.newFolder("linux")
        val input = CancelBetweenReadsInputStream()
        val source = SingleStreamAssetSource("large-asset", input)
        val extraction = launch(Dispatchers.Default) {
            RuntimeExtractor(source).extract(target).toList()
        }

        assertTrue(input.firstRead.await(1, TimeUnit.SECONDS))
        extraction.cancel()
        // flowOn propagates cancellation to its IO producer asynchronously.
        Thread.sleep(50)
        input.releaseFirstRead.countDown()
        extraction.join()

        assertEquals("cancelled extraction must not request another chunk", 0, input.laterReads.get())
    }

    private fun rootfsTar(): ByteArray = tarOf(
        TarDirectory("bin/"),
        TarFile("bin/tool", "runtime-tool".toByteArray(), mode = 0b111101101),
        TarSymbolicLink("bin/tool-symlink", "tool"),
        TarHardLink("bin/tool-hardlink", "bin/tool"),
    )

    private fun tarOf(vararg entries: TestTarEntry): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            entries.forEach { spec ->
                val entry = TarArchiveEntry(spec.name, spec.typeFlag).apply {
                    mode = spec.mode
                    size = spec.content.size.toLong()
                    if (spec.linkName != null) linkName = spec.linkName
                }
                tar.putArchiveEntry(entry)
                if (spec.content.isNotEmpty()) tar.write(spec.content)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return bytes.toByteArray()
    }
}

private sealed class TestTarEntry(
    val name: String,
    val typeFlag: Byte,
    val content: ByteArray = byteArrayOf(),
    val mode: Int = 0b110100100,
    val linkName: String? = null,
)

private class TarDirectory(name: String) : TestTarEntry(
    name = name,
    typeFlag = TarArchiveEntry.LF_DIR,
    mode = 0b111101101,
)

private class TarFile(name: String, content: ByteArray, mode: Int = 0b110100100) : TestTarEntry(
    name = name,
    typeFlag = TarArchiveEntry.LF_NORMAL,
    content = content,
    mode = mode,
)

private class TarSymbolicLink(name: String, target: String) : TestTarEntry(
    name = name,
    typeFlag = TarArchiveEntry.LF_SYMLINK,
    linkName = target,
)

private class TarHardLink(name: String, target: String) : TestTarEntry(
    name = name,
    typeFlag = TarArchiveEntry.LF_LINK,
    linkName = target,
)

/** Test-only [AssetSource] backed by an in-memory map. */
private class CancelBetweenReadsInputStream : java.io.InputStream() {
    val firstRead = CountDownLatch(1)
    val releaseFirstRead = CountDownLatch(1)
    val laterReads = AtomicInteger(0)
    private var first = true

    override fun read(): Int = error("bulk reads only")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (first) {
            first = false
            firstRead.countDown()
            check(releaseFirstRead.await(1, TimeUnit.SECONDS))
            buffer[offset] = 1
            return 1
        }
        laterReads.incrementAndGet()
        return -1
    }
}

private class SingleStreamAssetSource(
    private val name: String,
    private val stream: java.io.InputStream,
) : AssetSource {
    override fun entries(): List<AssetEntry> = listOf(AssetEntry(name, 1))
    override fun open(name: String): java.io.InputStream = stream
}

private class MapAssetSource(
    vararg pairs: Pair<String, ByteArray>,
) : AssetSource {
    private val files: Map<String, ByteArray> = pairs.toMap()

    override fun entries(): List<AssetEntry> =
        files.map { (name, bytes) ->
            AssetEntry(name, bytes.size.toLong())
        }

    override fun open(name: String): java.io.InputStream =
        ByteArrayInputStream(files.getValue(name))
}
