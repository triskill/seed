package cz.trety.seed.runtime

import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.trety.seed.data.AndroidSettingsRepo
import cz.trety.seed.ui.settings.SettingsForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    fun runsGuestPythonPiAndFlaskReloadFromAndroidAppDomain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val domain = File("/proc/self/attr/current").readText().trim()
        assertTrue("expected untrusted_app domain, got $domain", domain.contains("untrusted_app"))

        val runtimeDir = File(context.cacheDir, "native-proot-smoke/runtime")
        runBlocking {
            RuntimeExtractor(AndroidAssetSource(context.assets)).extract(runtimeDir).collect()
        }
        val rootfs = File(runtimeDir, "rootfs")
        val nativeProot = NativeProot.resolve(context.applicationInfo.nativeLibraryDir)

        // The APK must contain a direct-native runtime matching this device,
        // never a legacy QEMU guest marker.
        val versionJson = context.assets.open("linux/seed_version.json").use {
            it.reader().readText()
        }
        val version = RootfsVersion.parse(versionJson)
        assertEquals(RootfsVersion.NATIVE_RUNTIME_FORMAT, version.runtimeFormat)
        assertEquals(RootfsVersion.NATIVE_RUNTIME_FORMAT_VERSION, version.runtimeFormatVersion)
        val expectedNativeArch = if ("x86_64" in Build.SUPPORTED_ABIS) "x86_64" else "arm64"
        assertEquals(expectedNativeArch, version.nativeArch)

        val environment = ProotEnvironment.createBackend(
            tempDir = File(context.cacheDir, "native-proot-smoke/tmp"),
            installation = nativeProot,
        )
        val credentialEnvironment = environment + SettingsForm(
            provider = "opencode-go",
            model = "deepseek-v4-flash",
            apiKey = "instrumentation-not-a-real-key",
        ).toPiRuntimeEnvironment()
        val pythonOutput = runGuest(
            domain = domain,
            rootfs = rootfs,
            nativeProot = nativeProot,
            environment = credentialEnvironment,
            command = listOf(
                "/usr/bin/python3",
                "-c",
                """
                    import os
                    assert os.environ["PI_CODING_AGENT_DIR"] == "/home/seed/.pi/agent"
                    assert "SEED_PI_PROVIDER" not in os.environ
                    assert "SEED_PI_MODEL" not in os.environ
                    assert "OPENCODE_API_KEY" not in os.environ
                    print("APP_DOMAIN_PROOT_ENV_OK")
                """.trimIndent(),
            ),
        )
        assertEquals("APP_DOMAIN_PROOT_ENV_OK", pythonOutput.trim())

        // This executes PiRunner *inside* Android PRoot. The runtime has no API
        // key during instrumentation. Probe state without contacting a provider;
        // correlated RPC responses belong to rpc_request(), not read_lines().
        val piSmokeScript = """
            import asyncio
            import sys
            sys.path.insert(0, "/home/seed/backend")
            from seed_backend.orchestrator import pi_cmd_for_role, pi_env_for_role
            from seed_backend.pi_runner import PiRunner

            async def main():
                runner = PiRunner(
                    cmd=pi_cmd_for_role("middleman"),
                    role="middleman",
                    env=pi_env_for_role("middleman", app_url="http://127.0.0.1:7778"),
                    read_only_tools={"read", "grep", "find", "ls"},
                )
                try:
                    await runner.start()
                    event = await runner.rpc_request({"type": "get_state"}, timeout=20)
                    assert event.get("type") == "response"
                    assert event.get("command") == "get_state"
                    assert event.get("success") is True
                    assert isinstance(event.get("data"), dict)
                    print("APP_DOMAIN_PI_RPC_OK")
                finally:
                    await runner.stop()

            asyncio.run(main())
        """.trimIndent()
        val piOutput = runGuest(
            domain = domain,
            rootfs = rootfs,
            nativeProot = nativeProot,
            environment = environment,
            command = listOf("/usr/bin/python3", "-c", piSmokeScript),
            timeoutSeconds = PI_PROCESS_TIMEOUT_SECONDS,
        )
        assertTrue(piOutput, piOutput.contains("APP_DOMAIN_PI_RPC_OK"))

        // Flask must work as a separate subprocess in the same Android PRoot
        // environment. This is the acceptance check for the :7778 WebView and
        // worker-verification endpoint: it proves both readiness and a real
        // Python source edit observed by Flask's development reloader.
        val flaskSmokeScript = """
            import asyncio
            from pathlib import Path
            from urllib.request import urlopen
            import sys

            sys.path.insert(0, "/home/seed/backend")
            from seed_backend.flask_manager import FlaskManager

            app_dir = Path("/tmp/seed-flask-reload-smoke")
            package = app_dir / "seed_app"
            package.mkdir(parents=True, exist_ok=True)
            (package / "__init__.py").write_text("")
            app_file = package / "app.py"

            def write_app(message):
                app_file.write_text(
                    "from flask import Flask\n"
                    "app = Flask(__name__)\n"
                    "@app.get('/api/ping')\n"
                    "def ping():\n"
                    "    return {'pong': True}\n"
                    "@app.get('/reload-probe')\n"
                    "def reload_probe():\n"
                    "    return " + repr(message) + "\n"
                )

            def get_body():
                with urlopen("http://127.0.0.1:17778/reload-probe", timeout=2) as response:
                    return response.read().decode()

            async def main():
                write_app("before")
                manager = FlaskManager(port=17778, app_dir=str(app_dir), poll_interval=0.05)
                try:
                    assert await manager.start()
                    assert await asyncio.to_thread(get_body) == "before"
                    # Ensure the source timestamp advances on filesystems with
                    # one-second mtime resolution before asking Werkzeug to reload.
                    await asyncio.sleep(1.1)
                    write_app("after")
                    deadline = asyncio.get_running_loop().time() + 12
                    while await asyncio.to_thread(get_body) != "after":
                        if asyncio.get_running_loop().time() >= deadline:
                            raise AssertionError("Flask reloader did not serve edited source")
                        await asyncio.sleep(0.1)
                    print("APP_DOMAIN_FLASK_RELOAD_OK")
                finally:
                    await manager.stop()

            asyncio.run(main())
        """.trimIndent()
        val flaskOutput = runGuest(
            domain = domain,
            rootfs = rootfs,
            nativeProot = nativeProot,
            environment = environment,
            command = listOf("/usr/bin/python3", "-c", flaskSmokeScript),
            timeoutSeconds = PI_PROCESS_TIMEOUT_SECONDS,
        )
        assertTrue(flaskOutput, flaskOutput.contains("APP_DOMAIN_FLASK_RELOAD_OK"))
    }

    @Test
    fun persistedPreferencesStayOutOfPiEnvironment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtimeDir = File(context.cacheDir, "native-proot-persist/runtime")
        runBlocking {
            RuntimeExtractor(AndroidAssetSource(context.assets)).extract(runtimeDir).collect()
        }
        val rootfs = File(runtimeDir, "rootfs")
        val nativeProot = NativeProot.resolve(context.applicationInfo.nativeLibraryDir)

        // Exercise the production repository with isolated preference storage.
        // Never overwrite the phone's configured provider/model or credentials.
        val preferencesDir = File(context.cacheDir, "selection-test-${System.nanoTime()}")
        check(preferencesDir.mkdirs())
        val storeJob = Job()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + storeJob),
            produceFile = { File(preferencesDir, "settings.preferences_pb") },
        )
        val repo = AndroidSettingsRepo(context, store)
        try {
            runBlocking {
                assertEquals(null, repo.load())
                repo.save(
                    SettingsForm(
                        provider = "opencode-go",
                        model = "deepseek-v4-flash",
                        apiKey = "instrumentation-not-a-real-key",
                        // Non-default thinking checks the persisted preference boundary.
                        thinkingLevel = "high",
                    ),
                )
            }
            val persisted = requireNotNull(runBlocking { repo.load() }) {
                "SettingsForm did not round-trip through AndroidSettingsRepo"
            }
            assertEquals("opencode-go", persisted.provider)
            assertEquals("deepseek-v4-flash", persisted.model)
            assertEquals("high", persisted.thinkingLevel)
            assertTrue(
                "Android must not persist the test credential",
                persisted.apiKey != "instrumentation-not-a-real-key",
            )

            // Preferences round-trip locally; Pi selection/auth belong to shared
            // files, not credential or selection environment overrides.
            val environment = ProotEnvironment.createBackend(
                tempDir = File(context.cacheDir, "native-proot-persist/tmp"),
                installation = nativeProot,
            ) + persisted.toPiRuntimeEnvironment()
            val output = runGuest(
                domain = File("/proc/self/attr/current").readText().trim(),
                rootfs = rootfs,
                nativeProot = nativeProot,
                environment = environment,
                command = listOf(
                    "/usr/bin/python3",
                    "-c",
                    """
                        import os
                        assert os.environ["PI_CODING_AGENT_DIR"] == "/home/seed/.pi/agent"
                        assert "SEED_PI_PROVIDER" not in os.environ
                        assert "SEED_PI_MODEL" not in os.environ
                        assert "SEED_PI_THINKING" not in os.environ
                        assert "OPENCODE_API_KEY" not in os.environ
                        print("APP_DOMAIN_PERSIST_OK")
                    """.trimIndent(),
                ),
            )
            assertEquals("APP_DOMAIN_PERSIST_OK", output.trim())
        } finally {
            runBlocking { storeJob.cancelAndJoin() }
            check(preferencesDir.deleteRecursively()) { "Could not remove test preferences" }
        }
    }

    private fun runGuest(
        domain: String,
        rootfs: File,
        nativeProot: NativeProotInstallation,
        environment: Map<String, String>,
        command: List<String>,
        timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS,
    ): String {
        // Use the production native-only prefix. PRoot needs /dev and /proc
        // for its usual guest process setup.
        val process = ProcessBuilder(
            ProotCommand.base(nativeProot.executable, rootfs).apply {
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
