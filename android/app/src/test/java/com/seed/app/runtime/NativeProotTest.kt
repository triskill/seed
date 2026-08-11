package com.seed.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeProotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolveReturnsPackagedPairDirectlyUnderNativeLibraryDir() {
        val nativeLibraryDir = tempFolder.newFolder("native-libs")
        val executable = File(nativeLibraryDir, "libproot.so").apply { writeText("proot") }
        val loader = File(nativeLibraryDir, "libproot-loader.so").apply { writeText("loader") }

        val installation = NativeProot.resolve(nativeLibraryDir.absolutePath)

        assertEquals(
            NativeProotInstallation(executable = executable, loader = loader),
            installation,
        )
    }

    @Test
    fun resolveRejectsMissingNativeExecutableWithClearPath() {
        val nativeLibraryDir = tempFolder.newFolder("missing-native-libs")
        File(nativeLibraryDir, "libproot-loader.so").writeText("loader")
        val expected = File(nativeLibraryDir, "libproot.so")

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    @Test
    fun resolveRejectsDirectoryAtNativeExecutablePathWithClearPath() {
        val nativeLibraryDir = tempFolder.newFolder("directory-native-libs")
        File(nativeLibraryDir, "libproot.so").mkdir()
        File(nativeLibraryDir, "libproot-loader.so").writeText("loader")
        val expected = File(nativeLibraryDir, "libproot.so")

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    @Test
    fun resolveRejectsMissingPackagedLoaderWithClearPath() {
        val nativeLibraryDir = tempFolder.newFolder("missing-loader-native-libs")
        File(nativeLibraryDir, "libproot.so").writeText("proot")
        val expected = File(nativeLibraryDir, "libproot-loader.so")

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    @Test
    fun resolveRejectsDirectoryAtPackagedLoaderPathWithClearPath() {
        val nativeLibraryDir = tempFolder.newFolder("directory-loader-native-libs")
        File(nativeLibraryDir, "libproot.so").writeText("proot")
        val expected = File(nativeLibraryDir, "libproot-loader.so").apply { mkdir() }

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    private fun assertRejectedWithPath(nativeLibraryDir: File, expected: File) {
        val failure = assertThrows(IllegalStateException::class.java) {
            NativeProot.resolve(nativeLibraryDir.absolutePath)
        }

        assertTrue(failure.message.orEmpty().contains("regular file"))
        assertTrue(failure.message.orEmpty().contains(expected.absolutePath))
    }
}
