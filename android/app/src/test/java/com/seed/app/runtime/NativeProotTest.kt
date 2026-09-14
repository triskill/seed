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
    fun resolveReturnsCompleteBundleDirectlyUnderNativeLibraryDir() {
        val nativeLibraryDir = tempFolder.newFolder("native-libs")
        val expected = createCompleteInstallation(nativeLibraryDir)

        val installation = NativeProot.resolve(nativeLibraryDir.absolutePath)

        assertEquals(expected, installation)
    }

    @Test
    fun resolveRejectsMissingNativeExecutableWithClearPath() {
        assertMissingRejected("libproot.so")
    }

    @Test
    fun resolveRejectsDirectoryAtNativeExecutablePathWithClearPath() {
        assertDirectoryRejected("libproot.so")
    }

    @Test
    fun resolveRejectsMissingPackagedLoaderWithClearPath() {
        assertMissingRejected("libproot-loader.so")
    }

    @Test
    fun resolveRejectsDirectoryAtPackagedLoaderPathWithClearPath() {
        assertDirectoryRejected("libproot-loader.so")
    }

    @Test
    fun resolveRejectsMissingTallocWithClearPath() {
        assertMissingRejected("libtalloc.so")
    }

    @Test
    fun resolveRejectsDirectoryAtTallocPathWithClearPath() {
        assertDirectoryRejected("libtalloc.so")
    }

    @Test
    fun resolveRejectsMissingAndroidShmemWithClearPath() {
        assertMissingRejected("libandroid-shmem.so")
    }

    @Test
    fun resolveRejectsDirectoryAtAndroidShmemPathWithClearPath() {
        assertDirectoryRejected("libandroid-shmem.so")
    }

    private fun assertMissingRejected(filename: String) {
        val nativeLibraryDir = tempFolder.newFolder("missing-${filename.replace('.', '-')}")
        createCompleteInstallation(nativeLibraryDir)
        val expected = File(nativeLibraryDir, filename)
        assertTrue(expected.delete())

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    private fun assertDirectoryRejected(filename: String) {
        val nativeLibraryDir = tempFolder.newFolder("directory-${filename.replace('.', '-')}")
        createCompleteInstallation(nativeLibraryDir)
        val expected = File(nativeLibraryDir, filename)
        assertTrue(expected.delete())
        assertTrue(expected.mkdir())

        assertRejectedWithPath(nativeLibraryDir, expected)
    }

    private fun createCompleteInstallation(nativeLibraryDir: File): NativeProotInstallation {
        val executable = File(nativeLibraryDir, "libproot.so").apply { writeText("proot") }
        val loader = File(nativeLibraryDir, "libproot-loader.so").apply { writeText("loader") }
        val talloc = File(nativeLibraryDir, "libtalloc.so").apply { writeText("talloc") }
        val androidShmem = File(nativeLibraryDir, "libandroid-shmem.so").apply { writeText("shmem") }
        return NativeProotInstallation(
            executable = executable,
            loader = loader,
            talloc = talloc,
            androidShmem = androidShmem,
        )
    }

    private fun assertRejectedWithPath(nativeLibraryDir: File, expected: File) {
        val failure = assertThrows(IllegalStateException::class.java) {
            NativeProot.resolve(nativeLibraryDir.absolutePath)
        }

        assertTrue(failure.message.orEmpty().contains("regular file"))
        assertTrue(failure.message.orEmpty().contains(expected.absolutePath))
    }
}
