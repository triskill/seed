package cz.trety.seed.runtime

import java.io.File

/** APK-owned recovery code only; never re-extract the rootfs or touch user workspaces. */
object BackendPatchInstaller {
    private val modules = listOf("orchestrator.py", "pi_runner.py")
    fun install(rootfs: File, readModule: (String) -> ByteArray) {
        val content = modules.associateWith { name -> readModule(name).also {
            require(it.isNotEmpty() && it.size <= 512 * 1024) { "Invalid backend patch asset" }
        } }
        val destination = File(rootfs.canonicalFile, "home/seed/backend/seed_backend")
        require(destination.canonicalFile == destination.absoluteFile) { "Backend module directory must not contain symlinks" }
        check(destination.isDirectory || destination.mkdirs()) { "Could not create backend module directory" }
        content.forEach { (name, bytes) ->
            val temporary = File.createTempFile("seed-backend-", ".tmp", destination)
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(File(destination, name))) { "Could not replace backend module" }
            } finally { temporary.delete() }
        }
    }
}
