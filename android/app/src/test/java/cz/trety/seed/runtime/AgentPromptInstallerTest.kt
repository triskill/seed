package cz.trety.seed.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentPromptInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deploysOnlyRolePromptsAndPreservesGeneratedAppAndSettings() {
        val root = temporary.newFolder()
        val app = File(root, "home/seed/app/habits.json").apply { parentFile!!.mkdirs(); writeText("keep habits") }
        val auth = File(root, "home/seed/.pi/agent/auth.json").apply { parentFile!!.mkdirs(); writeText("keep credentials") }
        val prompts = File(root, "home/seed/backend/prompts").apply { mkdirs() }
        File(prompts, "worker.md").writeText("old")
        AgentPromptInstaller.install(root) { "new:$it".toByteArray() }
        assertEquals("new:worker.md", File(prompts, "worker.md").readText())
        assertEquals("new:middleman.md", File(prompts, "middleman.md").readText())
        assertEquals("keep habits", app.readText())
        assertEquals("keep credentials", auth.readText())
        assertEquals(setOf("worker.md", "middleman.md"), prompts.list()!!.toSet())
    }

    @Test fun rejectsSymlinkedPromptDirectoryWithoutWritingIntoApp() {
        val root = temporary.newFolder()
        val app = File(root, "home/seed/app").apply { mkdirs() }
        val backend = File(root, "home/seed/backend").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(backend, "prompts").toPath(), app.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            AgentPromptInstaller.install(root) { "new".toByteArray() }
        }
        assertTrue(app.list()!!.isEmpty())
    }

    @Test fun validatesBothAssetsBeforeReplacingAnyPrompt() {
        val root = temporary.newFolder()
        val prompts = File(root, "home/seed/backend/prompts").apply { mkdirs() }
        val worker = File(prompts, "worker.md").apply { writeText("old") }
        assertThrows(IllegalArgumentException::class.java) {
            AgentPromptInstaller.install(root) { if (it == "middleman.md") byteArrayOf() else "new".toByteArray() }
        }
        assertEquals("old", worker.readText())
    }
}
