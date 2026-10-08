package cz.trety.seed.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/** Selected app-owned runtime paths only; not a same-UID isolation boundary. */
internal object RuntimeFilePermissions {
    fun ownerFile(path: Path) = apply(path, directory = false)

    fun ownerDirectory(path: Path) = apply(path, directory = true)

    private fun apply(path: Path, directory: Boolean) {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw IOException("POSIX permissions unavailable: $path")
        val attributes = view.readAttributes()
        if (attributes.isSymbolicLink || if (directory) !attributes.isDirectory else !attributes.isRegularFile) {
            throw IOException("Unexpected runtime path type: $path")
        }
        if (directory) {
            // A no-follow POSIX chmod may need to open the directory, which fails
            // at mode 000 on host JVMs. Bootstrap owner access only, then replace
            // the entire mode below. Like the existing deletion path, this check
            // and File chmod are not atomic against same-UID path substitution.
            val file = path.toFile()
            if (!file.setReadable(true, true) || !file.setWritable(true, true) ||
                !file.setExecutable(true, true)) {
                throw IOException("Could not restore owner directory access: $path")
            }
        }
        val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE)
        view.setPermissions(permissions)
    }
}
