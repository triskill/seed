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
        val loader = tempFolder.newFile("libproot-loader.so")

        ProotEnvironment.create(tempDir, loader)

        assertTrue(tempDir.isDirectory)
    }

    @Test
    fun createSetsExactRuntimeEnvironmentIncludingAbsoluteTemporaryAndLoaderPaths() {
        val tempDir = File(tempFolder.root, "proot")
        val loader = tempFolder.newFile("libproot-loader.so")

        val environment = ProotEnvironment.create(tempDir, loader)

        assertEquals(
            mapOf(
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "dumb",
                "PROOT_TMP_DIR" to tempDir.absolutePath,
                "PROOT_LOADER" to loader.absolutePath,
            ),
            environment,
        )
    }

    @Test
    fun createAcceptsExistingTemporaryDirectory() {
        val tempDir = tempFolder.newFolder("existing-proot")
        val loader = tempFolder.newFile("libproot-loader.so")

        val environment = ProotEnvironment.create(tempDir, loader)

        assertEquals(tempDir.absolutePath, environment["PROOT_TMP_DIR"])
    }

    @Test
    fun createRejectsRegularFileAsTemporaryDirectoryWithClearAbsolutePath() {
        val tempDir = tempFolder.newFile("not-a-directory")
        val loader = tempFolder.newFile("libproot-loader.so")

        val failure = assertThrows(IllegalStateException::class.java) {
            ProotEnvironment.create(tempDir, loader)
        }

        assertTrue(failure.message.orEmpty().contains("temporary directory"))
        assertTrue(failure.message.orEmpty().contains(tempDir.absolutePath))
    }

    @Test
    fun createRejectsMissingPackagedLoaderWithClearAbsolutePath() {
        val tempDir = File(tempFolder.root, "proot-missing-loader")
        val loader = File(tempFolder.root, "missing-libproot-loader.so")

        assertLoaderRejectedWithPath(tempDir, loader)
    }

    @Test
    fun createRejectsDirectoryAsPackagedLoaderWithClearAbsolutePath() {
        val tempDir = File(tempFolder.root, "proot-directory-loader")
        val loader = tempFolder.newFolder("directory-libproot-loader.so")

        assertLoaderRejectedWithPath(tempDir, loader)
    }

    private fun assertLoaderRejectedWithPath(tempDir: File, loader: File) {
        val failure = assertThrows(IllegalStateException::class.java) {
            ProotEnvironment.create(tempDir, loader)
        }

        assertTrue(failure.message.orEmpty().contains("loader"))
        assertTrue(failure.message.orEmpty().contains("regular file"))
        assertTrue(failure.message.orEmpty().contains(loader.absolutePath))
    }
}
