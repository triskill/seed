package com.seed.app.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Decides whether the runtime needs (re-)extraction and drives the
 * extraction flow.
 *
 * Constructed in [com.seed.app.MainActivity] with the asset source
 * and the current `seed_version.json` from the APK. The `states`
 * flow is observed by the UI, which may call [runExtraction] again
 * when lifecycle collection restarts.
 *
 * The `filesDir/linux/.version` file is written after a successful
 * extraction so the next launch sees an up-to-date install and
 * skips the work. If extraction is cancelled or fails, the file is
 * NOT written and the next launch re-tries.
 */
class BootController(
    targetDir: File,
    source: AssetSource,
    private val assetVersion: RootfsVersion,
    // Default is test-only. Production passes a lifecycle-aware
    // scope so activity destruction cancels extraction promptly.
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val extractionFlow: (File) -> Flow<ExtractionProgress> =
        RuntimeExtractor(source)::extract,
) {
    private val targetDir = targetDir.canonicalFile
    private val installationMutex = RuntimeInstallationCoordinator.mutexFor(this.targetDir)
    private val extractionJobMonitor = Any()
    private var extractionJob: Job? = null

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
        synchronized(extractionJobMonitor) {
            if (_states.value !is BootState.NeedsExtraction || extractionJob != null) return

            val job = scope.launch(start = CoroutineStart.LAZY) {
                installationMutex.withLock {
                    // Another controller may have completed while this one waited.
                    if (isUpToDate()) {
                        _states.value = BootState.Ready
                        return@withLock
                    }

                    extractionFlow(targetDir).collect { progress ->
                        _states.value = BootState.Extracting(progress)
                        if (progress is ExtractionProgress.Finished) {
                            writeVersionFile()
                            _states.value = BootState.Ready
                        }
                    }
                }
            }
            extractionJob = job
            job.invokeOnCompletion {
                synchronized(extractionJobMonitor) {
                    if (extractionJob === job) extractionJob = null
                }
            }
            // Register before starting: even an undispatched caller cannot launch a duplicate.
            job.start()
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

/**
 * Serializes installation writers across every controller in this app process.
 * Activity recreation creates independent controllers and scopes, but aliases of
 * one canonical target share this mutex. A process-local lock is sufficient:
 * process death also removes every coroutine that could still write the runtime.
 */
private object RuntimeInstallationCoordinator {
    private val monitor = Any()
    private val targetMutexes = mutableMapOf<String, Mutex>()

    fun mutexFor(canonicalTargetDir: File): Mutex = synchronized(monitor) {
        targetMutexes.getOrPut(canonicalTargetDir.canonicalPath) { Mutex() }
    }
}
