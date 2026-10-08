package cz.trety.seed.runtime

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeFilePermissionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `directory recovery removes other access including after mode zero`() {
        val directory = temporary.newFolder("owned").toPath()
        for (mode in listOf("rwxrwxrwx", "---------")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(mode))
            RuntimeFilePermissions.ownerDirectory(directory)
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
        }
    }

    @Test fun `static symlinks are rejected before bootstrapping target permissions`() {
        val target = temporary.newFolder("target").toPath()
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
        val link = temporary.root.toPath().resolve("link")
        Files.createSymbolicLink(link, target)
        assertNotNull(runCatching { RuntimeFilePermissions.ownerDirectory(link) }.exceptionOrNull())
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    @Test fun `file mode replaces existing group and other access`() {
        val file = temporary.newFile("owned").toPath()
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxrwxrwx"))
        RuntimeFilePermissions.ownerFile(file)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertNotNull(runCatching { RuntimeFilePermissions.ownerDirectory(file) }.exceptionOrNull())
    }
}
