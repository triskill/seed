package cz.trety.seed.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Never touches filesDir/linux, the user's service, settings, or actual generated app. */
@RunWith(AndroidJUnit4::class)
class PackagedRuntimeRestoreTest {
    @Test fun packagedRestorePreservesOnlyAllowlistedGuestDataAndHostData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(context.cacheDir, "restore-test-${UUID.randomUUID()}").apply { mkdirs() }
        val linux = File(isolated, "linux").apply { mkdir() }
        try {
            fun put(name: String, text: String) = File(linux, name).apply { parentFile!!.mkdirs(); writeText(text) }
            put("rootfs/home/seed/app/.git/config", "test-git")
            put("rootfs/home/seed/app/database.db", "test-database")
            put("rootfs/home/seed/backend/config.json", "{\"test\":true}")
            put("rootfs/discard-me", "broken-runtime")
            put("pi-agent/auth.json", "isolated-credentials")
            File(isolated, "android-data").writeText("outside-runtime")
            val source = AndroidAssetSource(context.assets)
            val version = context.assets.open("linux/seed_version.json").bufferedReader().use { RootfsVersion.parse(it.readText()) }
            requireRestoreAssets(source)
            RuntimeMaintenanceGate.exclusive(allowFrozen = true) {
                RuntimeRestoreTransaction(linux, sourceRootfsVersion = version, publishCommittedMetadata = { publishRuntimeMetadata(linux, it) })
                    .restore({ true }) { stage ->
                        RuntimeExtractor(source).extract(stage).collect { }
                        validateRestoredRuntime(File(stage, "rootfs"))
                    }
            }
            assertEquals("test-git", File(linux, "rootfs/home/seed/app/.git/config").readText())
            assertEquals("test-database", File(linux, "rootfs/home/seed/app/database.db").readText())
            assertEquals("{\"test\":true}", File(linux, "rootfs/home/seed/backend/config.json").readText())
            assertEquals("isolated-credentials", File(linux, "pi-agent/auth.json").readText())
            assertEquals("outside-runtime", File(isolated, "android-data").readText())
            assertFalse(File(linux, "rootfs/discard-me").exists())
            assertFalse(File(linux, ".restore-old").exists())
            assertFalse(File(linux, ".restore-journal").exists())
            assertEquals(version, RootfsVersion.parse(File(linux, ".version").readText()))
        } finally { isolated.deleteRecursively() }
    }
}
