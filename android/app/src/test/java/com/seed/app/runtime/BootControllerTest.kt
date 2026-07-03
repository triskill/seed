package com.seed.app.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

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
    override fun entries() = files.map { AssetEntry(it.key, it.value.size.toLong(), false) }
    override fun open(name: String) = ByteArrayInputStream(files.getValue(name))
}
