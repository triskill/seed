package com.seed.app.runtime

/**
 * Progress events emitted by [RuntimeExtractor.extract].
 *
 * - [Started] — emitted exactly once at the start, before any file
 *   is written. `totalBytes` is the sum of all asset sizes; `fileCount`
 *   is the number of files that will be written. This is the only event
 *   the UI needs to size its progress bar before extraction starts.
 * - [FileProgress] — emitted after each file finishes writing.
 *   `bytesDone` is the cumulative bytes written so far (across all
 *   files), and `totalBytes` matches the [Started] event. The UI
 *   renders `bytesDone / totalBytes` for a smooth bar.
 * - [Finished] — emitted exactly once after the last file is written
 *   and flushed. The target directory is fully populated and any
 *   post-processing (chmod) has been applied.
 */
sealed class ExtractionProgress {
    abstract val totalBytes: Long

    data class Started(
        override val totalBytes: Long,
        val fileCount: Int,
    ) : ExtractionProgress()

    data class FileProgress(
        val name: String,
        val bytesDone: Long,
        override val totalBytes: Long,
    ) : ExtractionProgress()

    data object Finished : ExtractionProgress() {
        // totalBytes is unknown here; the UI uses the [Started] value
        // it cached. We override to avoid carrying a redundant field.
        override val totalBytes: Long get() = -1L
    }
}
