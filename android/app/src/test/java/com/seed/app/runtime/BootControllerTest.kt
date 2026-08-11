package com.seed.app.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BootControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun freshInstallTransitionsNeedsExtractionToExtractingToReady() = runTest(UnconfinedTestDispatcher()) {
        val target = tempFolder.newFolder("linux")
        // No .version file present → NeedsExtraction.
        val source = MapAssetSource2("a" to "x".toByteArray())

        val controller = BootController(
            targetDir = target,
            source = source,
            assetVersion = RootfsVersion("0.1.0", "B1"),
            scope = this,    // share the test scope so the launched coroutine runs on the test dispatcher
        )

        // First state: needs extraction (no .version file on disk).
        assertTrue(controller.states.first() is BootState.NeedsExtraction)

        // Drive the extraction. We can't read states.value synchronously
        // here: RuntimeExtractor.extract() uses flowOn(Dispatchers.IO),
        // so its upstream runs on a real IO thread pool that the
        // UnconfinedTestDispatcher cannot intercept. We must suspend
        // until the state actually transitions to Ready.
        controller.runExtraction()
        val last = controller.states.filter { it is BootState.Ready }.first()
        assertTrue("expected Ready, got $last", last is BootState.Ready)
        val versionText = target.resolve(".version").readText()
        assertTrue("version file missing build_id: $versionText", versionText.contains("\"build_id\":\"B1\""))
    }

    @Test
    fun repeatedRunBeforeFirstProgressStartsInvokesExtractionOnce() = runTest {
        val target = tempFolder.newFolder("single-flight")
        val releaseBeforeStarted = CompletableDeferred<Unit>()
        var invocations = 0
        val controller = BootController(
            targetDir = target,
            source = MapAssetSource2(),
            assetVersion = RootfsVersion("0.1.0", "B1"),
            scope = this,
            extractionFlow = {
                flow {
                    invocations += 1
                    releaseBeforeStarted.await()
                    emit(ExtractionProgress.Started(totalBytes = 0, fileCount = 0))
                }
            },
        )

        controller.runExtraction()
        controller.runExtraction()
        runCurrent()
        assertEquals(1, invocations)
        releaseBeforeStarted.complete(Unit)
        advanceUntilIdle()

        // A queued duplicate would acquire the shared mutex now and run because
        // this deliberately incomplete flow did not write a version marker.
        assertEquals(1, invocations)
    }

    @Test
    fun controllersForSameCanonicalTargetNeverExtractConcurrently() = runTest {
        val target = tempFolder.newFolder("shared-target")
        val releaseFirst = CompletableDeferred<Unit>()
        var activeExtractions = 0
        var maximumActiveExtractions = 0
        var invocations = 0
        val extractionFlow: (File) -> Flow<ExtractionProgress> = {
            flow {
                invocations += 1
                activeExtractions += 1
                maximumActiveExtractions = maxOf(maximumActiveExtractions, activeExtractions)
                try {
                    releaseFirst.await()
                    emit(ExtractionProgress.Started(totalBytes = 0, fileCount = 0))
                    emit(ExtractionProgress.Finished)
                } finally {
                    activeExtractions -= 1
                }
            }
        }
        val first = BootController(
            targetDir = target,
            source = MapAssetSource2(),
            assetVersion = RootfsVersion("0.1.0", "B1"),
            scope = this,
            extractionFlow = extractionFlow,
        )
        val second = BootController(
            targetDir = target.resolve("."),
            source = MapAssetSource2(),
            assetVersion = RootfsVersion("0.1.0", "B2"),
            scope = this,
            extractionFlow = extractionFlow,
        )

        first.runExtraction()
        second.runExtraction()
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, invocations)
        assertEquals(1, maximumActiveExtractions)
        assertTrue(first.states.value is BootState.Ready)
        assertTrue(second.states.value is BootState.Ready)
    }

    @Test
    fun replacementWaitsForCancelledExtractionCleanupBeforeEntering() = runTest {
        val target = tempFolder.newFolder("cancellation-cleanup")
        val firstEntered = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var secondObservedFinishedCleanup = false
        val firstScopeJob = Job(coroutineContext[Job])
        val secondScopeJob = Job(coroutineContext[Job])
        val extractionDispatcher = StandardTestDispatcher(testScheduler)
        val first = BootController(
            targetDir = target,
            source = MapAssetSource2(),
            assetVersion = RootfsVersion("0.1.0", "B1"),
            scope = CoroutineScope(coroutineContext + firstScopeJob),
            extractionFlow = {
                flow {
                    firstEntered.complete(Unit)
                    emit(ExtractionProgress.Started(totalBytes = 0, fileCount = 0))
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                            cleanupFinished.complete(Unit)
                        }
                    }
                }.flowOn(extractionDispatcher)
            },
        )
        val second = BootController(
            targetDir = target.resolve("."),
            source = MapAssetSource2(),
            assetVersion = RootfsVersion("0.1.0", "B1"),
            scope = CoroutineScope(coroutineContext + secondScopeJob),
            extractionFlow = {
                flow {
                    secondObservedFinishedCleanup = cleanupFinished.isCompleted
                    secondEntered.complete(Unit)
                    emit(ExtractionProgress.Started(totalBytes = 0, fileCount = 0))
                    emit(ExtractionProgress.Finished)
                }
            },
        )

        first.runExtraction()
        runCurrent()
        assertTrue(firstEntered.isCompleted)
        firstScopeJob.cancel()
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)

        second.runExtraction()
        runCurrent()
        val enteredBeforeCleanupFinished = secondEntered.isCompleted
        releaseCleanup.complete(Unit)
        advanceUntilIdle()

        assertFalse(enteredBeforeCleanupFinished)
        assertTrue(cleanupFinished.isCompleted)
        assertTrue(secondEntered.isCompleted)
        assertTrue(secondObservedFinishedCleanup)
        assertTrue(second.states.value is BootState.Ready)
        firstScopeJob.join()
        secondScopeJob.cancel()
        secondScopeJob.join()
    }

    @Test
    fun waiterRechecksVersionAndSkipsRedundantExtraction() = runTest {
        val target = tempFolder.newFolder("version-recheck")
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondInvocations = 0
        val version = RootfsVersion("0.1.0", "B1")
        val first = BootController(
            targetDir = target,
            source = MapAssetSource2(),
            assetVersion = version,
            scope = this,
            extractionFlow = {
                flow {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    emit(ExtractionProgress.Started(totalBytes = 0, fileCount = 0))
                    emit(ExtractionProgress.Finished)
                }
            },
        )
        val second = BootController(
            targetDir = target.resolve("."),
            source = MapAssetSource2(),
            assetVersion = version,
            scope = this,
            extractionFlow = {
                flow {
                    secondInvocations += 1
                    emit(ExtractionProgress.Started(totalBytes = 0, fileCount = 0))
                    emit(ExtractionProgress.Finished)
                }
            },
        )

        first.runExtraction()
        runCurrent()
        assertTrue(firstEntered.isCompleted)
        second.runExtraction()
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, secondInvocations)
        assertTrue(second.states.value is BootState.Ready)
        assertEquals(version, RootfsVersion.parse(target.resolve(".version").readText()))
    }

    @Test
    fun upToDateSkipsExtraction() = runTest(UnconfinedTestDispatcher()) {
        val target = tempFolder.newFolder("linux")
        target.resolve(".version").writeText("""{"seed_version":"0.1.0","build_id":"B1"}""")

        val controller = BootController(
            targetDir = target,
            source = MapAssetSource2(),
            assetVersion = RootfsVersion("0.1.0", "B1"),
        )

        // Version matches → state is Ready immediately, no extraction runs.
        val state = controller.states.value
        assertTrue("expected Ready, got $state", state is BootState.Ready)
    }
}

private class MapAssetSource2(
    vararg pairs: Pair<String, ByteArray>,
) : AssetSource {
    private val files = pairs.toMap()
    override fun entries() = files.map { AssetEntry(it.key, it.value.size.toLong()) }
    override fun open(name: String) = ByteArrayInputStream(files.getValue(name))
}
