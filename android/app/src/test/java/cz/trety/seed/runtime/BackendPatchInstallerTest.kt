package cz.trety.seed.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackendPatchInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun updatesOnlyTrustedModulesAndPreservesUserData() {
        val root = temporary.newFolder()
        val app = File(root, "home/seed/app/habits.json").apply { parentFile!!.mkdirs(); writeText("keep") }
        val auth = File(root, "home/seed/.pi/agent/auth.json").apply { parentFile!!.mkdirs(); writeText("keep auth") }
        BackendPatchInstaller.install(root) { "# trusted $it".toByteArray() }
        val destination = File(root, "home/seed/backend/seed_backend")
        assertEquals(setOf("orchestrator.py", "pi_runner.py"), destination.list()!!.toSet())
        assertEquals("# trusted orchestrator.py", File(destination, "orchestrator.py").readText())
        assertEquals("keep", app.readText()); assertEquals("keep auth", auth.readText())
    }
    @Test fun invalidAssetCannotPartiallyReplaceInstalledModules() {
        val root = temporary.newFolder()
        val destination = File(root, "home/seed/backend/seed_backend").apply { mkdirs() }
        val file = File(destination, "orchestrator.py").apply { writeText("original") }
        assertThrows(IllegalArgumentException::class.java) {
            BackendPatchInstaller.install(root) { if (it == "pi_runner.py") byteArrayOf() else "new".toByteArray() }
        }
        assertEquals("original", file.readText())
    }
    @Test fun refusesSymlinkedModuleDirectory() {
        val root = temporary.newFolder()
        val app = File(root, "home/seed/app").apply { mkdirs() }
        val backend = File(root, "home/seed/backend").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(backend, "seed_backend").toPath(), app.toPath())
        assertThrows(IllegalArgumentException::class.java) { BackendPatchInstaller.install(root) { "trusted".toByteArray() } }
        assertTrue(app.list()!!.isEmpty())
    }
}
