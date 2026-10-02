package com.seed.app.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Isolated host-child ownership/signaling smoke; never touches the installed rootfs or settings. */
@RunWith(AndroidJUnit4::class)
class OwnedRuntimeProcessSmokeTest {
    @Test
    fun ownsAndCleansUpPausedHostChild() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "owned-host-smoke-${System.nanoTime()}")
        var identity: OwnedProcessIdentity? = null
        val factory = OwnedRuntimeProcessFactory(
            root, Process.myPid(), Process.myUid(),
            inspect = { pid -> inspectProcIdentity(pid).also {
                if (identity == null && it is ProcessInspection.Present) identity = it.identity
            } },
            signal = { pid, signal -> Os.kill(pid, when (signal) {
                OwnedProcessSignal.QUIT -> OsConstants.SIGQUIT
                OwnedProcessSignal.CONTINUE -> OsConstants.SIGCONT
            }) },
            cleanupReady = ::hasQuitCleanupHandler,
        )
        val child = factory.start(
            listOf("/system/bin/sh", "-c",
                "trap 'exit 0' QUIT; printf 'OWNED_CHILD_READY\\n'; n=0; while [ ${'$'}n -lt 30 ]; do n=${'$'}((n + 1)); /system/bin/sleep 1; done"),
            context.cacheDir, emptyMap(),
        )
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.inputStream.available() == 0 && child.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("isolated child did not become ready", child.inputStream.available() > 0)
            assertEquals("OWNED_CHILD_READY", child.inputStream.bufferedReader().readLine())
            val owned = requireNotNull(identity)
            assertEquals(owned, (inspectProcIdentity(owned.pid) as ProcessInspection.Present).identity)
            Os.kill(owned.pid, OsConstants.SIGSTOP)
            child.destroy()
            assertTrue("QUIT/CONT did not stop the paused child", child.waitFor(10, TimeUnit.SECONDS))
            assertFalse(child.isAlive)
        } finally {
            identity?.let { owned ->
                if (child.isAlive && (inspectProcIdentity(owned.pid) as? ProcessInspection.Present)?.identity == owned) {
                    Os.kill(owned.pid, OsConstants.SIGQUIT)
                    if ((inspectProcIdentity(owned.pid) as? ProcessInspection.Present)?.identity == owned) {
                        Os.kill(owned.pid, OsConstants.SIGCONT)
                    }
                }
            }
            child.waitFor(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
}
