package com.seed.app.runtime

import java.io.File

data class NativeProotInstallation(
    val executable: File,
    val loader: File,
)

object NativeProot {
    private const val EXECUTABLE_FILENAME = "libproot.so"
    private const val LOADER_FILENAME = "libproot-loader.so"

    fun resolve(nativeLibraryDir: String): NativeProotInstallation {
        val executable = File(nativeLibraryDir, EXECUTABLE_FILENAME)
        check(executable.isFile) {
            "Native proot executable is not a regular file: ${executable.absolutePath}"
        }

        val loader = File(nativeLibraryDir, LOADER_FILENAME)
        check(loader.isFile) {
            "Native proot loader is not a regular file: ${loader.absolutePath}"
        }

        return NativeProotInstallation(executable = executable, loader = loader)
    }
}
