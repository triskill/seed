package com.seed.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotEnvironmentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createMakesMissingTemporaryDirectory() {
        val tempDir = File(tempFolder.root, "missing/proot")
        val installation = createInstallation()

        ProotEnvironment.create(tempDir, installation)

        assertTrue(tempDir.isDirectory)
    }

    @Test
    fun createSetsExactRuntimeEnvironmentIncludingNativeLibraryDirectory() {
        val tempDir = File(tempFolder.root, "proot")
        val installation = createInstallation()
        val nativeLibraryDir = installation.loader.parentFile!!.absolutePath

        val environment = ProotEnvironment.create(tempDir, installation)

        assertEquals(
            mapOf(
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "dumb",
                "PROOT_TMP_DIR" to tempDir.absolutePath,
                "PROOT_LOADER" to installation.loader.absolutePath,
                "LD_LIBRARY_PATH" to nativeLibraryDir,
            ),
            environment,
        )
    }

    @Test
    fun createAcceptsExistingTemporaryDirectory() {
        val tempDir = tempFolder.newFolder("existing-proot")
        val installation = createInstallation()

        val environment = ProotEnvironment.create(tempDir, installation)

        assertEquals(tempDir.absolutePath, environment["PROOT_TMP_DIR"])
    }

    @Test
    fun createRejectsRegularFileAsTemporaryDirectoryWithClearAbsolutePath() {
        val tempDir = tempFolder.newFile("not-a-directory")
        val installation = createInstallation()

        val failure = assertThrows(IllegalStateException::class.java) {
            ProotEnvironment.create(tempDir, installation)
        }

        assertTrue(failure.message.orEmpty().contains("temporary directory"))
        assertTrue(failure.message.orEmpty().contains(tempDir.absolutePath))
    }

    @Test
    fun createRejectsBundleWhoseFilesDoNotShareNativeLibraryDirectory() {
        val installation = createInstallation()
        val outsideTalloc = tempFolder.newFile("outside-libtalloc.so")
        val mismatched = installation.copy(talloc = outsideTalloc)

        val failure = assertThrows(IllegalStateException::class.java) {
            ProotEnvironment.create(File(tempFolder.root, "proot-mismatch"), mismatched)
        }

        assertTrue(failure.message.orEmpty().contains("native library directory"))
        assertTrue(failure.message.orEmpty().contains(outsideTalloc.absolutePath))
    }

    private fun createInstallation(): NativeProotInstallation {
        val nativeLibraryDir = tempFolder.newFolder("native-libs-${System.nanoTime()}")
        return NativeProotInstallation(
            executable = File(nativeLibraryDir, "libproot.so").apply { writeText("proot") },
            loader = File(nativeLibraryDir, "libproot-loader.so").apply { writeText("loader") },
            talloc = File(nativeLibraryDir, "libtalloc.so").apply { writeText("talloc") },
            androidShmem = File(nativeLibraryDir, "libandroid-shmem.so").apply { writeText("shmem") },
        )
    }
}
