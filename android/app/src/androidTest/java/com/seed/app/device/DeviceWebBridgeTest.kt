package com.seed.app.device

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Isolated loopback pages only: never loads or edits the generated app or its storage. */
@RunWith(AndroidJUnit4::class)
class DeviceWebBridgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private class LocalServer(private val page: (String) -> String) : AutoCloseable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:${socket.localPort}/"
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: "/"
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = page(path).toByteArray()
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    client.getOutputStream().write(body)
                }
            } catch (_: Exception) { }
        }
        override fun close() { socket.close(); worker.join(4000) }
    }
    private class Host(val action: suspend () -> Map<String, Any?>) : DeviceCapabilityHost {
        val calls = AtomicInteger()
        var closed = false
        override suspend fun invoke(method: String, params: Map<String, Any?>): Map<String, Any?> { calls.incrementAndGet(); return action() }
        override fun close() { closed = true }
    }
    private fun fixture(server: LocalServer, host: Host, action: (WebView, DeviceWebBridge) -> Unit) {
        lateinit var view: WebView
        lateinit var bridge: DeviceWebBridge
        main {
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            bridge = DeviceWebBridge(view, host, server.url)
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { bridge.onNavigation() }
            }
        }
        try { assumeTrue(bridge.supported); action(view, bridge) }
        finally { main { bridge.close(); view.destroy() } }
        assertTrue(host.closed)
    }
    private fun title(view: WebView): String {
        val latch = CountDownLatch(1)
        var result = ""
        main { view.evaluateJavascript("document.title") { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.trim('"')
    }
    private fun waitTitle(view: WebView, expected: String) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) { if (title(view) == expected) return; Thread.sleep(30) }
        assertEquals(expected, title(view))
    }
    private fun html(js: String) = "<!doctype html><title>loading</title><script>$js</script>"
    @Test fun sdkGetsVersionedNativeResult() {
        LocalServer { html("seed.android.call({method:'sensor.list',params:{}}).then(r=>document.title=r.sensors.length===0?'ok':'bad',e=>document.title=e.code)") }.use { server ->
            val host = Host { mapOf("sensors" to emptyList<Any>()) }
            fixture(server, host) { view, _ -> main { view.loadUrl(server.url) }; waitTitle(view, "ok"); assertEquals(1, host.calls.get()) }
        }
    }
    @Test fun sdkRepliesAndSanitizesRuntimeFailure() {
        LocalServer { html("seed.android.call({method:'sensor.list',params:{}}).then(()=>document.title='unexpected',e=>document.title=e.code+':'+e.message)") }.use { server ->
            val host = Host { throw IllegalStateException("private runtime secret") }
            fixture(server, host) { view, _ -> main { view.loadUrl(server.url) }; waitTitle(view, "INTERNAL_ERROR:Device operation failed"); assertEquals(1, host.calls.get()) }
        }
    }
    @Test fun backendPortAndSubframesCannotInvokeHost() {
        val host = Host { emptyMap() }
        val attempt = "if(window.seedDeviceTransport)seedDeviceTransport.postMessage(JSON.stringify({v:1,id:'frame',method:'sensor.list',params:{}}));document.title='done'"
        LocalServer { html(attempt) }.use { other ->
            LocalServer { path -> if (path.startsWith("/frame")) html(attempt) else "<!doctype html><title>loading</title><iframe src='/frame'></iframe><script>setTimeout(()=>document.title='done',300)</script>" }.use { server ->
                fixture(server, host) { view, _ ->
                    main { view.loadUrl(server.url) }; waitTitle(view, "done"); assertEquals(0, host.calls.get())
                    main { view.loadUrl(other.url) }; waitTitle(view, "done"); assertEquals(0, host.calls.get())
                }
            }
        }
    }
    @Test fun duplicateBusyMalformedAndNavigationCancellation() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val host = Host { started.countDown(); try { awaitCancellation() } finally { cancelled.countDown() } }
        LocalServer { html("document.title='ready'") }.use { server ->
            fixture(server, host) { view, bridge ->
                main { view.loadUrl(server.url) }; waitTitle(view, "ready")
                main { view.evaluateJavascript("""
                    var replies=[];seedDeviceTransport.onmessage=e=>{replies.push(JSON.parse(e.data));document.title=JSON.stringify(replies.map(r=>r.id+':'+r.error.code))};
                    seedDeviceTransport.postMessage(JSON.stringify({v:1,id:'first',method:'sensor.list',params:{}}));
                """.trimIndent(), null) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                main { view.evaluateJavascript("""
                    seedDeviceTransport.postMessage(JSON.stringify({v:1,id:'first',method:'sensor.list',params:{}}));
                    seedDeviceTransport.postMessage(JSON.stringify({v:1,id:'second',method:'sensor.list',params:{}}));
                    seedDeviceTransport.postMessage(JSON.stringify({v:1,id:'bad',method:'sensor.read',params:[]}));
                    try { seedDeviceTransport.postMessage(new Uint8Array([1,2,3]).buffer); } catch(e) {}
                """.trimIndent(), null) }
                // Duplicates are dropped, never replying with the first call's correlation ID.
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < end && !title(view).contains("bad:INVALID_REQUEST")) Thread.sleep(30)
                val result = title(view)
                assertTrue(result.contains("second:BUSY")); assertTrue(result.contains("bad:INVALID_REQUEST")); assertFalse(result.contains("first:"))
                assertEquals(1, host.calls.get())
                main { bridge.onNavigation() }
                assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            }
        }
    }
}
