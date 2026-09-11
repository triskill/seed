package com.seed.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seed.app.ui.settings.SettingsForm
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
    fun runsGuestPythonAndPipeBackedPiFromAndroidAppDomain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val domain = File("/proc/self/attr/current").readText().trim()
        assertTrue("expected untrusted_app domain, got $domain", domain.contains("untrusted_app"))

        val runtimeDir = File(context.cacheDir, "native-proot-smoke/runtime")
        runBlocking {
            RuntimeExtractor(AndroidAssetSource(context.assets)).extract(runtimeDir).collect()
        }
        val rootfs = File(runtimeDir, "rootfs")
        val nativeProot = NativeProot.resolve(context.applicationInfo.nativeLibraryDir)

        // Detect guest architecture from asset version marker so the test
        // also works with the QEMU x86_64 runtime built by
        // `make run-phone-x86-test`.
        val versionJson = context.assets.open("linux/seed_version.json").use {
            it.reader().readText()
        }
        val guestArch = RootfsVersion.parse(versionJson).guestArchitecture
        val qemuX86_64: File? = when (guestArch) {
            RootfsArchitecture.ARM64 -> null
            RootfsArchitecture.X86_64 ->
                NativeProot.resolveQemuX86_64(context.applicationInfo.nativeLibraryDir)
        }

        val environment = ProotEnvironment.createBackend(
            tempDir = File(context.cacheDir, "native-proot-smoke/tmp"),
            installation = nativeProot,
        ) + if (qemuX86_64 != null) {
            // Match RuntimeService: V8-generated x86 code crashes QEMU user-mode
            // on this ARM64 device, while --jitless is slow but stable.
            mapOf(
                "QEMU_CPU" to "max",
                "NODE_OPTIONS" to "--jitless",
            )
        } else {
            emptyMap()
        }
        val credentialEnvironment = environment + SettingsForm(
            provider = "opencode-go",
            model = "deepseek-v4-flash",
            apiKey = "instrumentation-not-a-real-key",
        ).toPiRuntimeEnvironment()
        val pythonOutput = runGuest(
            domain = domain,
            rootfs = rootfs,
            nativeProot = nativeProot,
            qemuX86_64 = qemuX86_64,
            environment = credentialEnvironment,
            command = listOf(
                "/usr/bin/python3",
                "-c",
                """
                    import os
                    assert os.environ["SEED_PI_PROVIDER"] == "opencode-go"
                    assert os.environ["SEED_PI_MODEL"] == "deepseek-v4-flash"
                    assert len(os.environ["OPENCODE_API_KEY"]) == 30
                    print("APP_DOMAIN_PROOT_ENV_OK")
                """.trimIndent(),
            ),
        )
        assertEquals("APP_DOMAIN_PROOT_ENV_OK", pythonOutput.trim())

        // This executes PiRunner *inside* Android PRoot. The runtime has no API
        // key during instrumentation, so a successful launch/RPC round trip is
        // the explicit provider error emitted by the real pi Node process.
        val piSmokeScript = """
            import asyncio
            import json
            import sys
            sys.path.insert(0, "/home/seed/backend")
            from seed_backend.orchestrator import pi_cmd_for_role, pi_env_for_role
            from seed_backend.pi_runner import PiRunner

            async def main():
                runner = PiRunner(
                    cmd=pi_cmd_for_role("middleman"),
                    role="middleman",
                    env=pi_env_for_role("middleman", app_url="http://127.0.0.1:7777"),
                    read_only_tools={"read", "grep", "find", "ls"},
                )
                try:
                    await runner.start()
                    await runner.send(json.dumps({"type": "prompt", "message": "hello"}))
                    diagnostics = []
                    async with asyncio.timeout(20):
                        async for line in runner.read_lines():
                            try:
                                event = json.loads(line)
                            except json.JSONDecodeError:
                                # PiRunner merges child stderr into its line stream.
                                # QEMU can emit Android-linker diagnostics before the
                                # JSONL RPC response; production translates these as
                                # ordinary text instead of treating them as protocol.
                                diagnostics.append(line)
                                continue
                            if event.get("type") == "response":
                                assert event.get("success") is False, event
                                assert "API key" in event.get("error", ""), event
                                print("APP_DOMAIN_PI_RPC_OK")
                                return
                    raise AssertionError(
                        f"pi RPC response stream ended; diagnostics={diagnostics[-3:]}"
                    )
                finally:
                    await runner.stop()

            asyncio.run(main())
        """.trimIndent()
        val piOutput = runGuest(
            domain = domain,
            rootfs = rootfs,
            nativeProot = nativeProot,
            qemuX86_64 = qemuX86_64,
            environment = environment,
            command = listOf("/usr/bin/python3", "-c", piSmokeScript),
            timeoutSeconds = PI_PROCESS_TIMEOUT_SECONDS,
        )
        assertTrue(piOutput, piOutput.contains("APP_DOMAIN_PI_RPC_OK"))
    }

    private fun runGuest(
        domain: String,
        rootfs: File,
        nativeProot: NativeProotInstallation,
        qemuX86_64: File?,
        environment: Map<String, String>,
        command: List<String>,
        timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS,
    ): String {
        // Use the production prefix: in particular, QEMU requires the
        // /proc bind for /proc/self/exe and PRoot needs /dev for its usual
        // guest process setup.
        val process = ProcessBuilder(
            ProotCommand.base(nativeProot.executable, rootfs, qemuX86_64).apply {
                addAll(command)
            },
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
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
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
        return stdout
    }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 30L
        const val PI_PROCESS_TIMEOUT_SECONDS = 45L
        const val DRAIN_TIMEOUT_MILLIS = 5_000L
    }
}
