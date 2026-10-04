package com.seed.app.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ

/**
 * Filesystem portion ONLY of Restore. Not connected to UI or runtime startup yet.
 * Caller must hold the application-wide rootfs-writer/launch exclusion gate and
 * prove backend (including failed launches) and PTY tracer exit before calling.
 * [confirmedStopped] is an injected prerequisite, NOT a process safety proof.
 *
 * Old runtime is a temporary transaction artifact, never a user backup. Recovery
 * rolls back until COMMITTED, and completes automatic cleanup after COMMITTED.
 * Outside-rootfs files are not changed. Legacy guest Pi data currently fails closed
 * rather than risking a lossy merge with the persistent host directory.
 */
internal class RuntimeRestoreTransaction(
    private val linux: File,
    private val availableBytes: () -> Long = { linux.usableSpace },
    private val checkpoint: (Checkpoint) -> Unit = {},
    private val sourceRootfsVersion: RootfsVersion? = null,
    private val publishCommittedMetadata: (RootfsVersion) -> Unit = {},

) {
    internal enum class Checkpoint { PREPARED, OLD_RENAMED, NEW_RENAMED, COMMITTED }
    private val base = trustedRuntimePath(linux).toPath().toAbsolutePath().normalize()
    private val root = base.resolve("rootfs")
    private val stage = base.resolve(".restore-stage")
    private val old = base.resolve(".restore-old")
    private val journal = base.resolve(".restore-journal")
    private val journalTemp = base.resolve(".restore-journal.tmp")

    suspend fun restore(confirmedStopped: suspend () -> Boolean, extract: suspend (File) -> Unit) {
        check(confirmedStopped()) { "Runtime and terminal exit must be confirmed before restore" }
        recover()
        requireSafeDirectory(root, optional = true)
        // A deleted runtime is repairable too. An empty rollback anchor keeps the
        // same rename/recovery protocol; no lost app files can be reconstructed.
        if (!exists(root)) Files.createDirectory(root)
        validatePreservedPaths(root)
        checkLegacyPi(root)
        requireSpace(RESERVE_BYTES)
        writeJournal("EXTRACTING", requireNotNull(sourceRootfsVersion) { "Restore source version is required" })
        try {
            Files.createDirectory(stage)
            extract(stage.toFile())
            currentCoroutineContext().ensureActive()
            val fresh = stage.resolve("rootfs")
            requireSafeDirectory(fresh)
            validatePreservedPaths(fresh)
            checkLegacyPi(fresh)
            preserve(root.resolve(APP), fresh.resolve(APP), directory = true)
            preserve(root.resolve(CONFIG), fresh.resolve(CONFIG), directory = false)
            currentCoroutineContext().ensureActive()
            syncTree(fresh)
            writeJournal("PREPARED", requireNotNull(sourceRootfsVersion))
            checkpoint(Checkpoint.PREPARED)
            // No suspension/cancellation point from first rename to journal commit.
            if (exists(root)) move(root, old)
            checkpoint(Checkpoint.OLD_RENAMED)
            move(fresh, root)
            checkpoint(Checkpoint.NEW_RENAMED)
            writeJournal("COMMITTED", requireNotNull(sourceRootfsVersion))
            checkpoint(Checkpoint.COMMITTED)
            recover()
        } catch (failure: Exception) {
            try { recover() } catch (recovery: Exception) { failure.addSuppressed(recovery) }
            throw failure
        }
    }

    /** Must run under the same exclusion gate, before ANY runtime launch/writer. */
    fun recover() {
        validateBase()
        if (!exists(journal)) {
            if (exists(old) || exists(stage)) {
                throw IOException("Unjournaled restore artifacts; refusing to guess recovery")
            }
            if (exists(journalTemp)) {
                if (readJournal(journalTemp) != "EXTRACTING") {
                    throw IOException("Unpublished restore journal has an unexpected phase")
                }
                Files.delete(journalTemp)
                syncDirectory(base)
            }
            return
        }
        val recorded = readJournalRecord(journal)
        val phase = recorded.first
        if (exists(journalTemp)) {
            // Atomic rename has not published this newer phase; the durable
            // journal still decides rollback vs commit. Validate before unlinking.
            readJournal(journalTemp)
            Files.delete(journalTemp)
            syncDirectory(base)
        }
        requireSafeDirectory(root, optional = true)
        requireSafeDirectory(old, optional = true)
        requireSafeDirectory(stage, optional = true)
        when (phase) {
            "EXTRACTING" -> {
                if (exists(old)) throw IOException("Old tree exists during extraction")
                deleteTree(stage)
            }
            "PREPARED", "ROLLING_BACK" -> {
                if (phase == "PREPARED") writeJournal("ROLLING_BACK", recorded.second)
                if (exists(old)) {
                    deleteTree(root)
                    move(old, root)
                } else if (!exists(root)) {
                    throw IOException("Restore rollback has no old root")
                }
                deleteTree(stage)
            }
            "COMMITTED" -> {
                if (!exists(root)) throw IOException("Committed restore is missing rootfs")
                publishCommittedMetadata(recorded.second)
                deleteTree(old)
                deleteTree(stage)
            }
            else -> throw IOException("Unknown restore journal phase")
        }
        Files.deleteIfExists(journalTemp)
        // Cleanup must be durable before retiring its ownership/source record.
        syncDirectory(base)
        Files.delete(journal)
        syncDirectory(base)
    }

    private suspend fun preserve(source: Path, destination: Path, directory: Boolean) {
        if (!exists(source)) return
        if (directory) requireSafeDirectory(source) else requireRegular(source)
        val bytes = if (directory) Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
        } else Files.size(source)
        requireSpace(Math.addExact(bytes, RESERVE_BYTES))
        deleteTree(destination)
        if (directory) copyTree(source, destination) else copyFile(source, destination)
    }

    private suspend fun copyTree(source: Path, destination: Path) {
        currentCoroutineContext().ensureActive()
        when {
            Files.isSymbolicLink(source) -> Files.createSymbolicLink(destination, Files.readSymbolicLink(source))
            Files.isDirectory(source, NOFOLLOW_LINKS) -> {
                Files.createDirectory(destination)
                Files.newDirectoryStream(source).use { children ->
                    for (child in children) copyTree(child, destination.resolve(child.fileName))
                }
            }
            Files.isRegularFile(source, NOFOLLOW_LINKS) -> copyFile(source, destination)
            else -> throw IOException("Unsupported preserved app entry: $source")
        }
    }

    private suspend fun copyFile(source: Path, destination: Path) {
        Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
            destination.toFile().outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    requireSpace(RESERVE_BYTES)
                    output.write(buffer, 0, count)
                    yield()
                }
                output.fd.sync()
            }
        }
        if (!destination.toFile().setExecutable(source.toFile().canExecute(), false)) {
            throw IOException("Could not preserve app executable permission: $destination")
        }
    }

    private fun checkLegacyPi(tree: Path) {
        safeAncestors(tree, tree.resolve("home/seed/.pi/agent"))
        val legacy = tree.resolve("home/seed/.pi/agent")
        if (!exists(legacy)) return
        requireSafeDirectory(legacy)
        Files.newDirectoryStream(legacy).use {
            if (it.iterator().hasNext()) throw IOException("Legacy guest Pi data requires safe migration before restore")
        }
    }

    private fun validatePreservedPaths(tree: Path) {
        for (relative in listOf(APP, CONFIG)) {
            val path = tree.resolve(relative)
            safeAncestors(tree, path)
            if (exists(path)) {
                if (relative == APP) requireSafeDirectory(path) else requireRegular(path)
            }
        }
    }

    private fun validateBase() {
        var current = base.root
        for (part in base) {
            current = current.resolve(part)
            requireSafeDirectory(current)
        }
    }

    private fun safeAncestors(tree: Path, path: Path) {
        var current = tree
        requireSafeDirectory(current, optional = true)
        for (part in tree.relativize(path.parent)) {
            current = current.resolve(part)
            requireSafeDirectory(current, optional = true)
        }
    }

    private fun requireSafeDirectory(path: Path, optional: Boolean = false) {
        if (optional && !exists(path)) return
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw IOException("Restore requires a real directory: $path")
        }
    }
    private fun requireRegular(path: Path) {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Restore requires a regular file: $path")
    }
    private fun requireSpace(bytes: Long) {
        if (availableBytes() < bytes) throw IOException("Not enough free space for safe runtime restore")
    }
    private fun exists(path: Path) = Files.exists(path, NOFOLLOW_LINKS)
    private fun move(source: Path, destination: Path) {
        if (exists(destination)) throw IOException("Restore rename destination already exists: $destination")
        Files.move(source, destination, ATOMIC_MOVE)
        syncDirectory(source.parent)
        if (source.parent != destination.parent) syncDirectory(destination.parent)
    }
    private fun readJournal(path: Path): String = readJournalRecord(path).first
    private fun readJournalRecord(path: Path): Pair<String, RootfsVersion> {
        requireRegular(path)
        if (Files.size(path) !in 1..2048) throw IOException("Invalid restore journal")
        val json = Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(2049)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count > 2048) throw IOException("Oversized restore journal")
            String(bytes, 0, count, Charsets.UTF_8)
        }
        val match = Regex("""\{"phase":"(EXTRACTING|PREPARED|ROLLING_BACK|COMMITTED)","sourceRootfsVersion":(\{.*\})\}""").matchEntire(json)
            ?: throw IOException("Invalid restore journal payload")
        val version = try { RootfsVersion.parse(match.groupValues[2]) }
            catch (failure: IllegalArgumentException) { throw IOException("Invalid restore source version", failure) }
        if (match.groupValues[2] != version.toMarkerJson() ||
            !Regex("[A-Za-z0-9._+-]{1,128}").matches(version.seedVersion) ||
            !Regex("[A-Za-z0-9._+-]{1,128}").matches(version.buildId))
            throw IOException("Invalid restore source version")
        return match.groupValues[1] to version
    }
    private fun writeJournal(phase: String, version: RootfsVersion) {
        val payload = """{"phase":"$phase","sourceRootfsVersion":${version.toMarkerJson()}}"""
        // Validate before any filesystem mutation, including first extraction.
        require(Regex("[A-Za-z0-9._+-]{1,128}").matches(version.seedVersion))
        require(Regex("[A-Za-z0-9._+-]{1,128}").matches(version.buildId))
        require(RootfsVersion.parse(version.toMarkerJson()) == version)
        if (exists(journalTemp)) throw IOException("Unexpected temporary restore journal")
        Files.newOutputStream(journalTemp, java.nio.file.StandardOpenOption.CREATE_NEW).use {
            it.write(payload.toByteArray(Charsets.UTF_8))
        }
        FileChannel.open(journalTemp, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(journalTemp, journal, ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(base)
    }
    private fun syncDirectory(path: Path) = FileChannel.open(path, READ).use { it.force(true) }
    private suspend fun syncTree(path: Path) {
        currentCoroutineContext().ensureActive()
        when {
            Files.isSymbolicLink(path) -> Unit
            Files.isDirectory(path, NOFOLLOW_LINKS) -> {
                Files.newDirectoryStream(path).use { for (child in it) syncTree(child) }
                syncDirectory(path)
            }
            Files.isRegularFile(path, NOFOLLOW_LINKS) -> FileChannel.open(path, READ).use { it.force(true) }
            else -> throw IOException("Unsupported staged runtime entry: $path")
        }
    }
    private fun deleteTree(path: Path) {
        if (!exists(path)) return
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            val file = path.toFile()
            if (!file.setReadable(true, true) || !file.setWritable(true, true) || !file.setExecutable(true, true)) {
                throw IOException("Could not clean temporary restore directory: $path")
            }
            Files.newDirectoryStream(path).use { for (child in it) deleteTree(child) }
        }
        Files.delete(path)
    }
    private companion object {
        const val APP = "home/seed/app"
        const val CONFIG = "home/seed/backend/config.json"
        const val RESERVE_BYTES = 64L * 1024 * 1024
    }
}
