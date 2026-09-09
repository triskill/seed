package com.seed.app.runtime

import java.io.File

data class NativeProotInstallation(
    val executable: File,
    val loader: File,
    val talloc: File,
    val androidShmem: File,
)

object NativeProot {
    private const val EXECUTABLE_FILENAME = "libproot.so"
    private const val LOADER_FILENAME = "libproot-loader.so"
    private const val TALLOC_FILENAME = "libtalloc.so"
    private const val ANDROID_SHMEM_FILENAME = "libandroid-shmem.so"
    // ARM64 Android-host executable that emulates x86_64 guest programs.
    // It is installed through jniLibs, not copied to writable app storage.
    private const val QEMU_X86_64_FILENAME = "libqemu-x86-64.so"

    fun resolve(nativeLibraryDir: String): NativeProotInstallation {
        val directory = File(nativeLibraryDir)
        return NativeProotInstallation(
            executable = requireRegularFile(directory, EXECUTABLE_FILENAME, "executable"),
            loader = requireRegularFile(directory, LOADER_FILENAME, "loader"),
            talloc = requireRegularFile(directory, TALLOC_FILENAME, "libtalloc dependency"),
            androidShmem = requireRegularFile(
                directory,
                ANDROID_SHMEM_FILENAME,
                "libandroid-shmem dependency",
            ),
        )
    }

    /** Resolve the QEMU user-mode executable required by an x86_64 rootfs. */
    fun resolveQemuX86_64(nativeLibraryDir: String): File =
        requireRegularFile(
            File(nativeLibraryDir),
            QEMU_X86_64_FILENAME,
            "qemu-x86_64 executable",
        )

    private fun requireRegularFile(directory: File, filename: String, label: String): File {
        val file = File(directory, filename)
        check(file.isFile) {
            "Native proot $label is not a regular file: ${file.absolutePath}"
        }
        return file
    }
}
