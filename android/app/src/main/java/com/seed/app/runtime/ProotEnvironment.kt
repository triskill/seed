package com.seed.app.runtime

import java.io.File

object ProotEnvironment {
    fun create(tempDir: File): Map<String, String> {
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
        )
    }
}
