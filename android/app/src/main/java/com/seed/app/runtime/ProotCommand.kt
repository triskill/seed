package com.seed.app.runtime

import java.io.File

/**
 * Builds the common PRoot argument prefix used by both the backend
 * launcher and the interactive shell subprocess.
 *
 * Produces the base flags that configure PRoot's userland-root
 * mode, mount bindings, and the `--kill-on-exit` termination policy
 * common to both child processes. The packaged guest and host are both
 * native ARM64; no foreign-architecture emulator is supported.
 *
 * This object is shared between:
 *  - [ProotRunner] which appends the backend launch command.
 *  - [SeedTerminalManager] which appends `/bin/sh -l`.
 *
 * Keeping it shared prevents the two from drifting apart on new flags.
 */
internal object ProotCommand {

    /**
     * Build the base PRoot argument list.
     *
     * @param executable           Path to `libproot.so` (or the proot loader binary).
     * @param rootfsDir            Path to the extracted Alpine rootfs directory.
     */
    fun base(
        executable: File,
        rootfsDir: File,
    ): MutableList<String> = buildList {
        // The proot executable or shared library.
        add(executable.absolutePath)

        // Root filesystem to chroot into.
        add("-r")
        add(rootfsDir.absolutePath)

        // Mount the host's /dev and /proc inside the chroot so that
        // PRoot's userland implementation can find PTYs, /proc/self, etc.
        add("-b")
        add("/dev")

        add("-b")
        add("/proc")

        // Kill child + descendants when PRoot exits. Prevents orphan
        // processes (uvicorn, /bin/sh, etc.) when the handle is destroyed.
        add("--kill-on-exit")
    }.toMutableList()
}
