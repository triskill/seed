package com.seed.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Installs runtime assets into the app's private files directory.
 *
 * Ordinary assets are copied to [extract]'s target directory. The special
 * `rootfs.tar` asset is streamed directly into `targetDir/rootfs/`; the TAR is
 * never retained on disk. AGP transparently gunzips the source
 * `rootfs.tar.gz` and exposes it through [AssetSource] as `rootfs.tar`.
 *
 * Rootfs extraction accepts regular files, directories, symbolic links, and
 * hard links (the entry types present in Alpine's minirootfs). Entry paths are
 * normalized and constrained to the rootfs directory, and extraction refuses
 * to write through a symlink created by an earlier entry. A failed extraction
 * removes the partial rootfs so the next boot starts from a clean directory.
 */
class RuntimeExtractor(
    private val source: AssetSource,
) {
    fun extract(targetDir: File): Flow<ExtractionProgress> = flow {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not create runtime directory: $targetDir")
        }

        val entries = source.entries()
        val totalBytes = entries.sumOf { it.size }
        emit(ExtractionProgress.Started(totalBytes, entries.size))

        var bytesDone = 0L
        for (entry in entries) {
            source.open(entry.name).use { input ->
                if (entry.name == ROOTFS_ARCHIVE_NAME) {
                    extractRootfs(input, File(targetDir, ROOTFS_DIRECTORY_NAME))
                } else {
                    copyAsset(input, File(targetDir, entry.name), entry.executable)
                }
            }
            bytesDone += entry.size
            emit(ExtractionProgress.FileProgress(entry.name, bytesDone, totalBytes))
        }
        emit(ExtractionProgress.Finished)
    }.flowOn(Dispatchers.IO)

    private suspend fun copyAsset(input: InputStream, output: File, executable: Boolean) {
        output.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Could not create asset directory: $parent")
            }
        }
        output.outputStream().use { sink ->
            copyCancellable(input, sink)
            sink.fd.sync()
        }
        if (executable && !output.setExecutable(true, false)) {
            throw IOException("Could not mark asset executable: $output")
        }
    }

    private suspend fun extractRootfs(input: InputStream, rootfsDir: File) {
        val root = rootfsDir.toPath().toAbsolutePath().normalize()
        deleteTree(root)
        Files.createDirectories(root)
        val extractedFiles = mutableSetOf<Path>()

        try {
            TarArchiveInputStream(input.buffered(BUFFER_SIZE)).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    extractTarEntry(tar, entry, root, extractedFiles)
                }
            }
        } catch (failure: Exception) {
            deleteTree(root)
            throw failure
        }
    }

    private suspend fun extractTarEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        root: Path,
        extractedFiles: MutableSet<Path>,
    ) {
        val output = resolveArchivePath(root, entry.name)
        ensureSafeParentDirectories(root, output.parent ?: root)

        when {
            entry.isDirectory -> {
                ensureSafeDirectories(root, output)
                applyExecutableMode(output, entry.mode)
            }

            entry.isSymbolicLink -> {
                Files.deleteIfExists(output)
                Files.createSymbolicLink(output, Paths.get(entry.linkName))
            }

            entry.isLink -> {
                val target = resolveArchivePath(root, entry.linkName)
                if (target !in extractedFiles) {
                    throw IOException("Hard-link target is not an extracted file: ${entry.linkName}")
                }
                Files.deleteIfExists(output)
                Files.createLink(output, target)
                extractedFiles.add(output)
            }

            entry.isFile -> {
                Files.deleteIfExists(output)
                Files.newOutputStream(output).use { sink ->
                    copyCancellable(tar, sink)
                }
                applyExecutableMode(output, entry.mode)
                extractedFiles.add(output)
            }

            else -> throw IOException("Unsupported TAR entry type: ${entry.name}")
        }
    }

    private fun resolveArchivePath(root: Path, entryName: String): Path {
        val relative = Paths.get(entryName).normalize()
        if (relative.isAbsolute || relative.startsWith("..")) {
            throw IOException("TAR entry escapes rootfs: $entryName")
        }
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) {
            throw IOException("TAR entry escapes rootfs: $entryName")
        }
        return resolved
    }

    private fun ensureSafeDirectories(root: Path, directory: Path) {
        ensureSafeParentDirectories(root, directory)
        if (directory == root) return
        when {
            Files.isSymbolicLink(directory) ->
                throw IOException("TAR entry traverses symbolic link: $directory")
            Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS) ->
                throw IOException("TAR directory conflicts with file: $directory")
            !Files.exists(directory, NOFOLLOW_LINKS) -> Files.createDirectory(directory)
        }
    }

    private fun ensureSafeParentDirectories(root: Path, parent: Path) {
        if (!parent.startsWith(root)) {
            throw IOException("TAR parent escapes rootfs: $parent")
        }
        var current = root
        for (part in root.relativize(parent)) {
            current = current.resolve(part)
            when {
                Files.isSymbolicLink(current) ->
                    throw IOException("TAR entry traverses symbolic link: $current")
                Files.exists(current, NOFOLLOW_LINKS) && !Files.isDirectory(current, NOFOLLOW_LINKS) ->
                    throw IOException("TAR parent conflicts with file: $current")
                !Files.exists(current, NOFOLLOW_LINKS) -> Files.createDirectory(current)
            }
        }
    }

    private suspend fun copyCancellable(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            // Give flowOn's producer a cancellation checkpoint before
            // requesting another chunk from AssetManager.
            yield()
        }
    }

    private fun applyExecutableMode(path: Path, mode: Int) {
        val executable = mode and EXECUTABLE_MODE_MASK != 0
        if (!path.toFile().setExecutable(executable, false)) {
            throw IOException("Could not apply executable mode to $path")
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        if (Files.isDirectory(root, NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(root).use { children ->
                children.forEach(::deleteTree)
            }
        }
        Files.deleteIfExists(root)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val ROOTFS_ARCHIVE_NAME = "rootfs.tar"
        const val ROOTFS_DIRECTORY_NAME = "rootfs"
        const val EXECUTABLE_MODE_MASK = 0b001001001
    }
}
