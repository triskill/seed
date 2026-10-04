package com.seed.app.ui.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    @Test fun portraitLandscapeRotationKeepsLivePageAndResizesWithoutReload() = withFixture { scenario, root, _ ->
        awaitUrl(scenario, root)
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario.onActivity { originalOrientation = it.requestedOrientation }
        try {
            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            lateinit var activity: RotationFixtureActivity
            lateinit var view: WebView
            var portraitWidth = 0
            scenario.onActivity {
                activity = it
                view = webView(it.window.decorView)!!
                portraitWidth = view.width
            }
            assertEquals("true", javascript(scenario, "window.liveHeap={token:'rotation-only'};document.getElementById('form').value='unsaved';window.scrollTo(0,600);true"))
            val scroll = javascript(scenario, "window.scrollY").toDouble()
            assertTrue("Fixture must actually scroll", scroll > 0)
            val loads = javascript(scenario, "window.documentToken")
            for ((requested, configuration) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
            )) {
                rotate(scenario, requested, configuration)
                scenario.onActivity {
                    assertSame(activity, it)
                    assertSame(view, webView(it.window.decorView))
                    if (configuration == Configuration.ORIENTATION_LANDSCAPE) assertTrue(view.width > portraitWidth)
                }
                assertEquals("\"rotation-only\"", javascript(scenario, "window.liveHeap.token"))
                assertEquals("\"unsaved\"", javascript(scenario, "document.getElementById('form').value"))
                assertEquals(loads, javascript(scenario, "window.documentToken"))
                assertEquals(scroll, javascript(scenario, "window.scrollY").toDouble(), 2.0)
            }
        } finally { scenario.onActivity { it.requestedOrientation = originalOrientation } }
    }

    @Test fun activeSensorStreamSurvivesRotationAndStopsInBackground() = withFixture(sensorGrant = true) { scenario, root, _ ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        org.junit.Assume.assumeTrue(manager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null)
        awaitUrl(scenario, root)
        var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        scenario.onActivity { originalOrientation = it.requestedOrientation }
        try {
            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            javascript(scenario, "window.samples=0;window.streamClosed=false;seed.android.subscribe({method:'sensor.subscribe',params:{type:1,rateHz:30}},()=>{window.samples++}).then(s=>{window.stream=s;s.closed.then(()=>{window.streamClosed=true})});true")
            awaitJavascript(scenario, "window.samples > 2")
            val before = javascript(scenario, "window.samples").toInt()
            rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            awaitJavascript(scenario, "window.samples > ${before + 2}")
            assertEquals("false", javascript(scenario, "window.streamClosed"))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitJavascript(scenario, "window.streamClosed")
            val stopped = javascript(scenario, "window.samples")
            Thread.sleep(300)
            assertEquals(stopped, javascript(scenario, "window.samples"))
        } finally { scenario.onActivity { it.requestedOrientation = originalOrientation } }
    }

    private fun awaitJavascript(scenario: ActivityScenario<RotationFixtureActivity>, expression: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (javascript(scenario, expression) == "true") return
            Thread.sleep(50)
        }
        fail("JavaScript condition did not become true: $expression")
    }

    private fun rotate(scenario: ActivityScenario<RotationFixtureActivity>, requested: Int, expected: Int) {
        scenario.onActivity { it.requestedOrientation = requested }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity {
                val view = webView(it.window.decorView)
                ready = it.resources.configuration.orientation == expected && view != null &&
                    if (expected == Configuration.ORIENTATION_LANDSCAPE) view.width > view.height else view.height > view.width
            }
            if (ready) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return }
            Thread.sleep(50)
        }
        fail("Orientation/layout did not reach $expected")
    }

    private fun javascript(scenario: ActivityScenario<RotationFixtureActivity>, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        scenario.onActivity { webView(it.window.decorView)!!.evaluateJavascript(script) { value -> result = value; latch.countDown() } }
        assertTrue("JavaScript callback timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }

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
        sensorGrant: Boolean = false,
        test: (ActivityScenario<RotationFixtureActivity>, String, androidx.compose.runtime.MutableState<Boolean>) -> Unit,
    ) {
        LoopbackPageServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = "rotation-fixture-${UUID.randomUUID()}"
            val store = PreferencesDeviceConsentStore(context, prefs)
            if (sensorGrant) store.grant(com.seed.app.device.canonicalDeviceOrigin(server.root), com.seed.app.device.DeviceConsentGroup.SENSORS)
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
    private val documentLoads = AtomicInteger()
    val root = "http://127.0.0.1:${server.localPort}/"
    private val worker = Thread {
        while (running.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                reader.readLine()
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><script>window.documentToken=${documentLoads.incrementAndGet()};</script></head><body style='height:5000px;margin:0'>Isolated rotation fixture<input id='form'><a href='/nested?filter=one#detail'>Nested</a></body></html>".toByteArray()
                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                it.getOutputStream().write(body)
            }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { running.set(false); server.close(); worker.join(2000) }
}
