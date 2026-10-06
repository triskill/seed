package cz.trety.seed.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.trety.seed.ui.app.RotationFixtureActivity
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Real PTY/PRoot acceptance tests. All writable runtime paths belong to this fixture. */
@RunWith(AndroidJUnit4::class)
class TerminalOwnedShutdownTest {
    @Test
    fun pausedTracerQuitContinueReapsGuestChildrenAndAllowsFreshSession() = withRuntime { context, manager ->
        lateinit var view: TerminalView
        RotationFixtureActivity.content = {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { activity ->
                TerminalView(activity, null).also { it.setTextSize(18); view = it; manager.attachView(it) }
            })
        }
        try {
            ActivityScenario.launch<RotationFixtureActivity>(
                Intent(context, RotationFixtureActivity::class.java),
            ).use { scenario ->
                lateinit var old: TerminalSession
                scenario.onActivity { old = manager.getOrCreateSession() }
                try {
                    await("readable native REPL prompt") { main { transcript(old).contains("seed:/home/seed#") } }
                } catch (error: AssertionError) {
                    throw AssertionError(main { "Isolated terminal pid=${old.pid} running=${old.isRunning} output=${transcript(old)}" }, error)
                }
                val tracer = requireNotNull(inspectProcIdentity(main { old.pid }) as? ProcessInspection.Present)
                assertTrue(tracer.identity.pid > 1)
                assertEquals(Process.myPid(), tracer.parentPid)
                assertEquals(Process.myUid(), tracer.identity.uid)
                val executable = NativeProot.resolve(context.applicationInfo.nativeLibraryDir).executable.absolutePath
                assertTrue("PTY PID must have exec'd the trusted tracer with QUIT cleanup", hasQuitCleanupHandler(tracer.identity, executable))

                // A trusted isolated child publishes its PID; verify its kernel ancestry,
                // rather than depending on Android exposing /proc/.../children.
                val receipt = File(context.filesDir, "linux/rootfs/home/seed/restore-child.pid")
                main { old.write("python3 -c 'import os,time;open(\"/home/seed/restore-child.pid\",\"w\").write(str(os.getpid()));time.sleep(60)'\r") }
                var descendants = emptyList<OwnedProcessIdentity>()
                await("live guest sleeper with verified tracer ancestry") {
                    val childPid = runCatching { receipt.readText().trim().toInt() }.getOrNull()
                    descendants = childPid?.let { ownedAncestors(it, tracer.identity) }.orEmpty()
                    descendants.isNotEmpty()
                }
                assertTrue("must exercise actual guest descendants", descendants.isNotEmpty())
                val ownedTree = descendants + tracer.identity
                // A stopped tracer cannot complete cleanup without the production CONT signal.
                assertEquals(tracer.identity, (inspectProcIdentity(tracer.identity.pid) as ProcessInspection.Present).identity)
                Os.kill(tracer.identity.pid, OsConstants.SIGSTOP)
                await("tracer stopped") { (inspectProcIdentity(tracer.identity.pid) as? ProcessInspection.Present)?.state in listOf('T', 't') }
                scenario.onActivity {
                    manager.freeze()
                    assertNull(view.mTermSession)
                    assertTrue("frozen attach must be rejected", runCatching { manager.attachView(view) }.exceptionOrNull() is IllegalStateException)
                }
                assertTrue("owned QUIT/CONT must await Termux waitpid completion", stop(manager))
                main { assertFalse(old.isRunning); assertFalse(manager.hasSession()) }
                await("all recorded owned native processes gone") { ownedTree.none(::sameProcessExists) }
                assertTrue("no child tree may survive the reaped tracer", descendantsOf(tracer.identity).isEmpty())

                lateinit var fresh: TerminalSession
                scenario.onActivity {
                    manager.unfreeze()
                    manager.attachView(view)
                    fresh = manager.getOrCreateSession()
                    assertNotSame(old, fresh)
                }
                await("fresh REPL prompt") { main { fresh.pid > 1 && transcript(fresh).contains("seed:/home/seed#") } }
                val freshIdentity = requireNotNull(inspectProcIdentity(main { fresh.pid }) as? ProcessInspection.Present).identity
                assertNotEquals(tracer.identity, freshIdentity)
                main { manager.freeze() }
                assertTrue("fresh generation must also reap", stop(manager))
                main { assertFalse(fresh.isRunning); assertFalse(manager.hasSession()) }
                await("fresh tracer gone") { !sameProcessExists(freshIdentity) }
            }
        } finally {
            RotationFixtureActivity.content = {}
        }
    }

    @Test
    fun freezeAndStopOfUnattachedPidZeroSessionNeverExecutes() = withRuntime { context, manager ->
        lateinit var lazy: TerminalSession
        main {
            lazy = manager.getOrCreateSession()
            assertEquals(0, lazy.pid)
            manager.freeze()
            assertTrue(runCatching { manager.attachView(TerminalView(context, null)) }.exceptionOrNull() is IllegalStateException)
        }
        assertTrue(stop(manager))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        main { assertEquals(0, lazy.pid); assertFalse(manager.hasSession()) }
        assertTrue("unstarted receipt directory must be discarded", File(context.cacheDir, "terminal-ownership").listFiles().orEmpty().isEmpty())
        main {
            manager.unfreeze()
            val fresh = manager.getOrCreateSession()
            assertNotSame(lazy, fresh)
            assertEquals(0, fresh.pid)
            manager.freeze()
        }
        assertTrue(stop(manager))
    }

    private fun withRuntime(test: (Context, SeedTerminalManager) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "terminal-owned-test-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = File(root, "cache").also { check(it.isDirectory || it.mkdirs()) }
            override fun getApplicationContext(): Context = this
        }
        val manager = SeedTerminalManager(context)
        try {
            runBlocking(Dispatchers.IO) {
                RuntimeExtractor(AndroidAssetSource(context.assets)).extract(File(root, "linux")).collect()
            }
            assertTrue(File(root, "linux/rootfs/usr/bin/python3").exists())
            test(context, manager)
        } finally {
            main { manager.freeze() }
            // Do not delete a rootfs while an unconfirmed native generation could still use it.
            if (stop(manager)) check(root.deleteRecursively())
            else fail("terminal cleanup unconfirmed; isolated fixture retained at $root")
        }
    }

    private fun stop(manager: SeedTerminalManager): Boolean = runBlocking(Dispatchers.IO) {
        manager.stopAndAwaitExit(15_000)
    }

    private fun <T> main(action: () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun transcript(session: TerminalSession): String = session.emulator?.screen?.transcriptText.orEmpty()

    /** Read only a verified tracer's descendant edges, never scan or signal arbitrary /proc PIDs. */
    private fun descendantsOf(parent: OwnedProcessIdentity): List<OwnedProcessIdentity> {
        if (!sameProcessExists(parent)) return emptyList()
        val result = mutableListOf<OwnedProcessIdentity>()
        val visited = mutableSetOf(parent.pid)
        fun visit(identity: OwnedProcessIdentity) {
            if (!sameProcessExists(identity)) return
            val children = runCatching { File("/proc/${identity.pid}/task/${identity.pid}/children").readText() }.getOrDefault("")
            for (pid in children.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }) {
                if (!visited.add(pid)) continue
                val child = inspectProcIdentity(pid) as? ProcessInspection.Present ?: continue
                if (child.parentPid != identity.pid || child.identity.uid != Process.myUid()) continue
                result += child.identity
                visit(child.identity)
            }
        }
        visit(parent)
        return result
    }

    private fun ownedAncestors(childPid: Int, tracer: OwnedProcessIdentity): List<OwnedProcessIdentity> {
        val result = mutableListOf<OwnedProcessIdentity>()
        var pid = childPid
        repeat(32) {
            val process = inspectProcIdentity(pid) as? ProcessInspection.Present ?: return emptyList()
            if (process.identity.uid != Process.myUid()) return emptyList()
            if (process.identity == tracer) return result
            if (process.parentPid <= 1 || result.any { it.pid == pid }) return emptyList()
            result += process.identity
            pid = process.parentPid
        }
        return emptyList()
    }

    private fun sameProcessExists(identity: OwnedProcessIdentity): Boolean =
        when (val inspected = inspectProcIdentity(identity.pid)) {
            is ProcessInspection.Present -> inspected.identity == identity
            ProcessInspection.Gone -> false
            // Inspection uncertainty is not evidence of cleanup.
            ProcessInspection.Unknown -> File("/proc/${identity.pid}").exists()
        }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for $description")
    }
}
