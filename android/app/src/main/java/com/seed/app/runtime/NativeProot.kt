package com.seed.app.runtime

import java.io.File

object NativeProot {
    private const val LIBRARY_FILENAME = "libproot.so"

    fun executable(nativeLibraryDir: String): File {
        val executable = File(nativeLibraryDir, LIBRARY_FILENAME)
        check(executable.isFile) {
            "Native proot executable is not a regular file: ${executable.absolutePath}"
        }
        return executable
    }
}
