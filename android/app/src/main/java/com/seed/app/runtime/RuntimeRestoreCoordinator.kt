package com.seed.app.runtime

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RestoreState {
    data object Idle : RestoreState
    data class Working(val message: String) : RestoreState
    data object Finished : RestoreState
    data class Failed(val message: String) : RestoreState
}

internal class RuntimeRestoreCoordinator(
    linux: File,
    private val source: AssetSource,
    private val version: RootfsVersion,
    private val freeze: () -> Unit,
    private val stop: suspend () -> Boolean,
    private val resume: () -> Unit,
) {
    private val linux = trustedRuntimePath(linux)
    private val mutableState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state = mutableState.asStateFlow()
    private val admission = java.util.concurrent.atomic.AtomicBoolean()

    suspend fun restore() {
        if (!admission.compareAndSet(false, true)) return
        try {
            RuntimeMaintenanceGate.exclusive(allowFrozen = true) {
                mutableState.value = RestoreState.Working("Stopping runtime and shell…")
                freeze()
                check(withTimeoutOrNull(30_000) { stop() } == true) {
                    "Could not confirm runtime and shell exit. Restore did not change Linux files. Retry Restore."
                }
                recoverRuntimeRestore(linux)
                mutableState.value = RestoreState.Working("Extracting packaged Linux runtime…")
                requireRestoreAssets(source)
                val archiveBytes = source.entries().sumOf { it.size }
                check(linux.usableSpace >= Math.addExact(archiveBytes, RESTORE_SPACE_RESERVE)) {
                    "Not enough free space to extract the packaged Linux runtime safely"
                }
                RuntimeRestoreTransaction(linux, sourceRootfsVersion = version, publishCommittedMetadata = {
                    publishRuntimeMetadata(linux, it)
                }).restore({ true }) { stage ->
                    RuntimeExtractor(source, checkSpace = {
                        if (stage.usableSpace < RESTORE_SPACE_RESERVE)
                            throw java.io.IOException("Not enough free space for safe runtime restore")
                    }).extract(stage).collect { progress ->
                        if (progress is ExtractionProgress.FileProgress)
                            mutableState.value = RestoreState.Working("Extracting ${progress.name}…")
                    }
                    validateRestoredRuntime(File(stage, "rootfs"))
                }
            }
            resume()
            mutableState.value = RestoreState.Finished
        } catch (cancelled: CancellationException) {
            mutableState.value = RestoreState.Failed("Restore interrupted; retry to recover safely.")
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = RestoreState.Failed(failure.message ?: "Restore failed; retry safely.")
        } finally { admission.set(false) }
    }
}

private const val RESTORE_SPACE_RESERVE = 64L * 1024 * 1024

internal fun requireRestoreAssets(source: AssetSource) {
    val names = source.entries().map { it.name }.toSet()
    check(names.containsAll(listOf("rootfs.tar", "seed_version.json"))) { "Packaged Linux assets are missing" }
}
internal fun validateRestoredRuntime(root: File) {
    for (relative in listOf("usr/bin/python", "usr/bin/node", "home/seed/backend/seed_backend/service.py")) {
        check(File(root, relative).isFile) { "Packaged runtime is missing $relative" }
    }
}
internal fun publishRuntimeMetadata(linux: File, version: RootfsVersion) {
    for ((name, contents) in listOf(".version" to version.toMarkerJson(), "seed_version.json" to version.toMarkerJson())) {
        val temp = File(linux, "$name.restore-tmp")
        if (java.nio.file.Files.exists(temp.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            check(java.nio.file.Files.isRegularFile(temp.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Unsafe metadata temporary file" }
            java.nio.file.Files.delete(temp.toPath())
        }
        java.nio.file.Files.newOutputStream(temp.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { it.write(contents.toByteArray()) }
        java.nio.channels.FileChannel.open(temp.toPath(), java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
        java.nio.file.Files.move(temp.toPath(), File(linux, name).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    java.nio.channels.FileChannel.open(linux.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
}
internal fun trustedRuntimePath(linux: File): File = File(requireNotNull(linux.parentFile).canonicalFile, linux.name)

internal fun recoverRuntimeRestore(linux: File) {
    val trusted = trustedRuntimePath(linux)
    if (java.nio.file.Files.exists(trusted.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        RuntimeRestoreTransaction(trusted, publishCommittedMetadata = { publishRuntimeMetadata(trusted, it) }).recover()
}
