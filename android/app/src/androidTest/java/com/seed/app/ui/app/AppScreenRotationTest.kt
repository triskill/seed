package com.seed.app.ui.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.seed.app.device.PreferencesDeviceConsentStore
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AppScreenRotationTest {
    @Test fun nestedRouteQueryFragmentAndBackHistorySurviveRecreation() = withFixture { scenario, root, _ ->
        awaitUrl(scenario, root)
        navigate(scenario, "${root}nested?filter=one#detail")
        awaitUrl(scenario, "${root}nested?filter=one#detail")
        scenario.recreate()
        awaitUrl(scenario, "${root}nested?filter=one#detail")
        scenario.onActivity { assertTrue(webView(it.window.decorView)!!.canGoBack()); webView(it.window.decorView)!!.goBack() }
        awaitUrl(scenario, root)
    }

    @Test fun delayedGateRetainsPendingRestoreAcrossAnotherSave() = withFixture(delayOnRecreation = true) { scenario, root, gate ->
        awaitUrl(scenario, root)
        navigate(scenario, "${root}nested?q=2#anchor")
        awaitUrl(scenario, "${root}nested?q=2#anchor")
        // The view is still live when Activity saves; the recreated child starts behind the gate.
        scenario.recreate()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { assertNull(webView(it.window.decorView)) }
        scenario.recreate() // Save while the restored child has not composed yet.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { gate.value = true }
        awaitUrl(scenario, "${root}nested?q=2#anchor")
        scenario.onActivity { assertTrue(webView(it.window.decorView)!!.canGoBack()) }
    }

    @Test fun tabDisposalAndReturnRetainBrowsingState() = withFixture { scenario, root, gate ->
        awaitUrl(scenario, root)
        navigate(scenario, "${root}nested?tab=1#return")
        awaitUrl(scenario, "${root}nested?tab=1#return")
        scenario.onActivity { gate.value = false }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { gate.value = true }
        awaitUrl(scenario, "${root}nested?tab=1#return")
    }

    @Test fun rejectedHistoryUsesSafeCurrentRouteOrConfiguredIndex() = withFixture { scenario, root, _ ->
        for ((current, expected) in listOf("${root}fallback?q=3#safe" to "${root}fallback?q=3#safe", "about:blank" to root, "https://evil.example/" to root)) {
            var detached: WebView? = null
            scenario.onActivity { activity ->
                val pending = android.os.Bundle().apply {
                    putBundle("history", android.os.Bundle())
                    putStringArrayList("urls", arrayListOf("https://evil.example/", "${root}nested"))
                    putInt("index", 1)
                    putString("current", current)
                }
                detached = WebView(activity).also { view ->
                    WebViewBrowsingState(pending).restoreOrLoad(view, root)
                }
            }
            try {
                val deadline = System.nanoTime() + 10_000_000_000L
                var actual: String? = null
                while (System.nanoTime() < deadline) {
                    scenario.onActivity { actual = detached!!.url }
                    if (actual == expected) break
                    Thread.sleep(50)
                }
                assertEquals(expected, actual)
            } finally { scenario.onActivity { detached!!.destroy() } }
        }
    }

    private fun withFixture(
        delayOnRecreation: Boolean = false,
        test: (ActivityScenario<RotationFixtureActivity>, String, androidx.compose.runtime.MutableState<Boolean>) -> Unit,
    ) {
        LoopbackPageServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = "rotation-fixture-${UUID.randomUUID()}"
            val store = PreferencesDeviceConsentStore(context, prefs)
            val gate = mutableStateOf(!delayOnRecreation)
            RotationFixtureActivity.content = { recreated ->
                val ready = if (delayOnRecreation && !recreated) true else gate.value
                RetainedAppNavigation(ready = ready, waiting = { Text("Binding fixture") }) {
                    AppScreen(expectedUrl = server.root, consentStore = store)
                }
            }
            try {
                ActivityScenario.launch<RotationFixtureActivity>(Intent(context, RotationFixtureActivity::class.java)).use {
                    test(it, server.root, gate)
                }
            } finally {
                RotationFixtureActivity.content = {}
                context.deleteSharedPreferences(prefs)
            }
        }
    }

    private fun navigate(scenario: ActivityScenario<RotationFixtureActivity>, url: String) {
        scenario.onActivity { webView(it.window.decorView)!!.evaluateJavascript("location.href = '$url'", null) }
    }

    private fun awaitUrl(scenario: ActivityScenario<RotationFixtureActivity>, expected: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var actual: String? = null
        while (System.nanoTime() < deadline) {
            var finished = false
            scenario.onActivity {
                val view = webView(it.window.decorView)
                actual = view?.url
                finished = view?.progress == 100
            }
            if (actual == expected && finished) return
            Thread.sleep(50)
        }
        assertEquals(expected, actual)
    }

    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) webView(view.getChildAt(i))?.let { return it }
        return null
    }
}

/** Serves only fixture HTML, never contacts the user's runtime. */
private class LoopbackPageServer : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val root = "http://127.0.0.1:${server.localPort}/"
    private val worker = Thread {
        while (running.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                reader.readLine()
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = "<!doctype html><html><body>Isolated rotation fixture<a href='/nested?filter=one#detail'>Nested</a></body></html>".toByteArray()
                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                it.getOutputStream().write(body)
            }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { running.set(false); server.close(); worker.join(2000) }
}
