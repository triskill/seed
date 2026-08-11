package com.seed.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeProotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun executableResolvesLibprootSoDirectlyUnderNativeLibraryDir() {
        val nativeLibraryDir = tempFolder.newFolder("native-libs")
        val expected = File(nativeLibraryDir, "libproot.so").apply { writeText("proot") }

        val executable = NativeProot.executable(nativeLibraryDir.absolutePath)

        assertEquals(expected, executable)
    }

    @Test
    fun executableRejectsMissingNativeLibraryWithClearPath() {
        val nativeLibraryDir = tempFolder.newFolder("missing-native-libs")
        val expected = File(nativeLibraryDir, "libproot.so")

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    @Test
    fun executableRejectsDirectoryAtNativeLibraryPathWithClearPath() {
        val nativeLibraryDir = tempFolder.newFolder("directory-native-libs")
        val expected = File(nativeLibraryDir, "libproot.so").apply { mkdir() }

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    private fun assertRejectedWithPath(nativeLibraryDir: File, expected: File) {
        val failure = assertThrows(IllegalStateException::class.java) {
            NativeProot.executable(nativeLibraryDir.absolutePath)
        }

        assertTrue(failure.message.orEmpty().contains("regular file"))
        assertTrue(failure.message.orEmpty().contains(expected.absolutePath))
    }
}
