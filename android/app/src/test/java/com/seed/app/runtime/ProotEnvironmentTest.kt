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

        ProotEnvironment.create(tempDir)

        assertTrue(tempDir.isDirectory)
    }

    @Test
    fun createSetsExactRuntimeEnvironmentIncludingAbsoluteTemporaryDirectory() {
        val tempDir = File(tempFolder.root, "proot")

        val environment = ProotEnvironment.create(tempDir)

        assertEquals(
            mapOf(
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "dumb",
                "PROOT_TMP_DIR" to tempDir.absolutePath,
            ),
            environment,
        )
    }

    @Test
    fun createAcceptsExistingTemporaryDirectory() {
        val tempDir = tempFolder.newFolder("existing-proot")

        val environment = ProotEnvironment.create(tempDir)

        assertEquals(tempDir.absolutePath, environment["PROOT_TMP_DIR"])
    }

    @Test
    fun createRejectsRegularFileWithClearAbsolutePath() {
        val tempDir = tempFolder.newFile("not-a-directory")

        val failure = assertThrows(IllegalStateException::class.java) {
            ProotEnvironment.create(tempDir)
        }

        assertTrue(failure.message.orEmpty().contains("temporary directory"))
        assertTrue(failure.message.orEmpty().contains(tempDir.absolutePath))
    }
}
