package com.seed.app.runtime

import java.io.File

object ProotEnvironment {
    fun create(tempDir: File, installation: NativeProotInstallation): Map<String, String> {
        val nativeLibraryDir = installation.executable.absoluteFile.parentFile
            ?: throw IllegalStateException(
                "Native proot executable has no native library directory: " +
                    installation.executable.absolutePath,
            )

        listOf(
            "executable" to installation.executable,
            "loader" to installation.loader,
            "libtalloc dependency" to installation.talloc,
            "libandroid-shmem dependency" to installation.androidShmem,
        ).forEach { (label, file) ->
            check(file.isFile) {
                "Native proot $label is not a regular file: ${file.absolutePath}"
            }
            check(file.absoluteFile.parentFile == nativeLibraryDir) {
                "Native proot $label is outside the native library directory " +
                    "${nativeLibraryDir.absolutePath}: ${file.absolutePath}"
            }
        }

        if (!tempDir.isDirectory && !tempDir.mkdirs() && !tempDir.isDirectory) {
            throw IllegalStateException(
                "Could not create proot temporary directory: ${tempDir.absolutePath}",
            )
        }

        return mapOf(
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "dumb",
            "PROOT_TMP_DIR" to tempDir.absolutePath,
            "PROOT_LOADER" to installation.loader.absolutePath,
            "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
        )
    }
}
