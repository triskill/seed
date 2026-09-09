package com.seed.app.runtime

import java.io.File

/**
 * Shared PRoot environment configuration for every child process.
 *
 * Provides two public entry points:
 * - [createBackend] — for the [ProotRunner] backend (TERM = "dumb").
 * - [createTerminal] — for the interactive shell (TERM = "xterm-256color").
 *
 * Both share a common base (HOME, PATH, LD_LIBRARY_PATH, PROOT_*)
 * that handles proot internal variables, native library resolution,
 * and temporary directory creation.
 *
 * Keeping the two modes separate (not a single API with overrides)
 * prevents accidental use of the wrong TERM value. The backend
 * must use TERM=dumb (uvicorn is a network daemon); the terminal
 * must use TERM=xterm-256color (interactive display requires
 * proper terminal capabilities for ANSI color and cursor control).
 */
object ProotEnvironment {

    /**
     * Create environment for the embedded backend process (uvicorn).
     *
     * TERM is set to "dumb" because this is a headless network service
     * — ANSI rendering is irrelevant and `dumb` avoids potential issues
     * with ncurses programs that probe terminal capabilities.
     */
    fun createBackend(
        tempDir: File,
        installation: NativeProotInstallation,
    ): Map<String, String> =
        createBase(tempDir, installation) + mapOf(
            "TERM" to "dumb",
        )

    /**
     * Create environment for the interactive shell session.
     *
     * TERM is set to "xterm-256color" so the shell and programs
     * like vim, less, and python can properly render ANSI color
     * escape sequences. COLORTERM=truecolor extends this to
     * 24-bit (true) color support. SHELL=/bin/sh provides the
     * standard shell variable expected by login shells.
     */
    fun createTerminal(
        tempDir: File,
        installation: NativeProotInstallation,
    ): Map<String, String> =
        createBase(tempDir, installation) + mapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "SHELL" to "/bin/sh",
        )

    /**
     * Common base environment shared by all PRoot child processes.
     *
     * Contains:
     * - HOME, LANG, PATH — standard shell environment.
     * - PROOT_TMP_DIR — temporary directory for proot's internal use.
     * - PROOT_LOADER — path to the proot loader shared library.
     * - LD_LIBRARY_PATH — so proot resolves libtalloc.so and other deps.
     *
     * This method also ensures PROOT_TMP_DIR exists on the filesystem.
     */
    private fun createBase(
        tempDir: File,
        installation: NativeProotInstallation,
    ): Map<String, String> {
        val nativeLibraryDir = installation.executable.absoluteFile.parentFile
            ?: throw IllegalStateException(
                "Native proot executable has no parent directory: " +
                    installation.executable.absolutePath,
            )

        // Verify all native proot files are present and in the same directory.
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
                "Native proot $label is outside native library directory " +
                    "${nativeLibraryDir.absolutePath}: ${file.absolutePath}"
            }
        }

        // Ensure temp directory exists and is a directory. A regular file at
        // this path would otherwise leak through to PRoot and fail much later.
        if (!tempDir.isDirectory && !tempDir.mkdirs() && !tempDir.isDirectory) {
            throw IllegalStateException(
                "Could not create proot temporary directory: ${tempDir.absolutePath}",
            )
        }

        return mapOf(
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_TMP_DIR" to tempDir.absolutePath,
            "PROOT_LOADER" to installation.loader.absolutePath,
            "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
        )
    }
}
