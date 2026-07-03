package com.seed.app.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Decides whether the runtime needs (re-)extraction and drives the
 * extraction flow.
 *
 * Constructed once in [com.seed.app.MainActivity] with the asset
 * source and the current `seed_version.json` from the APK. The
 * `states` flow is observed by the UI; the UI calls
 * [runExtraction] once on first composition (via `LaunchedEffect`).
 *
 * The `filesDir/linux/.version` file is written after a successful
 * extraction so the next launch sees an up-to-date install and
 * skips the work. If extraction is cancelled or fails, the file is
 * NOT written and the next launch re-tries.
 */
class BootController(
    private val targetDir: File,
    private val source: AssetSource,
    private val assetVersion: RootfsVersion,
    // Default is test-only. Production must pass a lifecycle-aware
    // scope (e.g. `ComponentActivity.lifecycleScope`) so the
    // extraction is cancelled when the activity is destroyed —
    // otherwise a recreated activity would race with the still-
    // running previous extraction and two writers could corrupt
    // the 150 MB rootfs tarball.
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val _states = MutableStateFlow<BootState>(initialState())
    val states: StateFlow<BootState> = _states.asStateFlow()

    private fun initialState(): BootState =
        if (isUpToDate()) BootState.Ready else BootState.NeedsExtraction

    private fun isUpToDate(): Boolean {
        val file = File(targetDir, VERSION_FILE)
        if (!file.exists()) return false
        return runCatching {
            RootfsVersion.parse(file.readText()) == assetVersion
        }.getOrDefault(false)
    }

    /**
     * Launch the extraction flow. Idempotent — calling again while
     * already running is a no-op. The UI's [BootState] observation
     * will tick through `Extracting(progress)` and end on
     * [BootState.Ready].
     */
    fun runExtraction() {
        if (_states.value !is BootState.NeedsExtraction) return
        scope.launch {
            RuntimeExtractor(source).extract(targetDir).collect { progress ->
                _states.value = BootState.Extracting(progress)
                if (progress is ExtractionProgress.Finished) {
                    writeVersionFile()
                    _states.value = BootState.Ready
                }
            }
        }
    }

    private fun writeVersionFile() {
        // Minimal hand-rolled JSON to keep this module dep-free.
        File(targetDir, VERSION_FILE).writeText(
            """{"seed_version":"${assetVersion.seedVersion}","build_id":"${assetVersion.buildId}"}""",
        )
    }

    private companion object {
        const val VERSION_FILE = ".version"
    }
}
