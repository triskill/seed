package com.seed.app.runtime

import java.io.File

/** Deploy only APK-bundled role instructions; never replace the user's workspace. */
object AgentPromptInstaller {
    private val roles = listOf("worker.md", "middleman.md")

    fun install(rootfs: File, readPrompt: (String) -> ByteArray) {
        // Validate every asset before changing installed files.
        val content = roles.associateWith { name ->
            readPrompt(name).also { require(it.isNotEmpty() && it.size <= 256 * 1024) { "Invalid agent prompt asset" } }
        }
        val destination = File(rootfs.canonicalFile, "home/seed/backend/prompts")
        require(destination.canonicalFile == destination.absoluteFile) { "Agent prompt directory must not contain symlinks" }
        check(destination.isDirectory || destination.mkdirs()) { "Could not create agent prompt directory" }
        content.forEach { (name, bytes) ->
            // Atomic replacement does not follow a pre-existing destination symlink.
            val temporary = File.createTempFile("seed-prompt-", ".tmp", destination)
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(File(destination, name))) { "Could not replace agent prompt" }
            } finally {
                temporary.delete()
            }
        }
    }
}
