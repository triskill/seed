package com.seed.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Copies runtime assets into the device's internal storage.
 *
 * The runtime assets (proot binary + Alpine rootfs tarball +
 * seed_version.json) live inside the APK at
 * `android/app/src/main/assets/linux/`. On first launch, or whenever
 * the asset version bumps, [extract] writes them to
 * `context.filesDir + "/linux/"` so the proot binary can be exec'd
 * and the rootfs can be untar'd (or used in-place — see Phase 8).
 *
 * The flow contract is:
 *   1. [ExtractionProgress.Started] emitted once with totals
 *   2. For each entry, in order: write → [ExtractionProgress.FileProgress]
 *   3. [ExtractionProgress.Finished] emitted once at the end
 *
 * Cancellation: collecting the flow cooperatively cancels the
 * write loop (the [FileProgress] emission happens AFTER each file
 * is fully written + flushed, so a cancel mid-write will leave a
 * partial file; the caller is responsible for cleaning the target
 * directory on cancel and retrying). For v0.1 we don't surface
 * cancel — the UI just blocks until done or fails.
 */
class RuntimeExtractor(
    private val source: AssetSource,
) {
    fun extract(targetDir: File): Flow<ExtractionProgress> = flow {
        if (!targetDir.exists()) targetDir.mkdirs()

        val entries = source.entries()
        val totalBytes = entries.sumOf { it.size }
        emit(ExtractionProgress.Started(totalBytes, entries.size))

        var bytesDone = 0L
        for (entry in entries) {
            val outFile = File(targetDir, entry.name)
            outFile.parentFile?.mkdirs()

            source.open(entry.name).use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.fd.sync()
                }
            }
            if (entry.executable) {
                outFile.setExecutable(true, false)
            }
            bytesDone += entry.size
            emit(ExtractionProgress.FileProgress(entry.name, bytesDone, totalBytes))
        }
        emit(ExtractionProgress.Finished)
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
