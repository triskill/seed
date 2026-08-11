package com.seed.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NativeProotSmokeTest {
    @Test
    fun runsGuestPythonFromAndroidAppDomain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val domain = File("/proc/self/attr/current").readText().trim()
        assertTrue("expected untrusted_app domain, got $domain", domain.contains("untrusted_app"))

        val runtimeDir = File(context.cacheDir, "native-proot-smoke/runtime")
        runBlocking {
            RuntimeExtractor(AndroidAssetSource(context.assets)).extract(runtimeDir).collect()
        }
        val rootfs = File(runtimeDir, "rootfs")
        val nativeProot = NativeProot.resolve(context.applicationInfo.nativeLibraryDir)
        val environment = ProotEnvironment.create(
            tempDir = File(context.cacheDir, "native-proot-smoke/tmp"),
            installation = nativeProot,
        )
        val process = ProcessBuilder(
            nativeProot.executable.absolutePath,
            "-r",
            rootfs.absolutePath,
            "/usr/bin/python3",
            "-c",
            "print('APP_DOMAIN_PROOT_OK')",
        ).directory(rootfs).apply {
            environment().clear()
            environment().putAll(environment)
        }.start()

        var stdout = ""
        var stderr = ""
        val stdoutDrain = thread(name = "proot-smoke-stdout") {
            stdout = process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrDrain = thread(name = "proot-smoke-stderr") {
            stderr = process.errorStream.bufferedReader().use { it.readText() }
        }
        val exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        stdoutDrain.join(DRAIN_TIMEOUT_MILLIS)
        stderrDrain.join(DRAIN_TIMEOUT_MILLIS)

        if (!exited || process.exitValue() != 0) {
            fail(
                "domain=$domain exited=$exited " +
                    "code=${if (exited) process.exitValue() else "alive"} " +
                    "stdout=<$stdout> stderr=<$stderr>",
            )
        }
        assertEquals("APP_DOMAIN_PROOT_OK", stdout.trim())
    }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 30L
        const val DRAIN_TIMEOUT_MILLIS = 5_000L
    }
}
