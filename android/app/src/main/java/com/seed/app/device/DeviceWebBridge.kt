package com.seed.app.device

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Main-thread owned, origin-bound transport. Replies stay attached to the sending document. */
class DeviceWebBridge(private val webView: WebView, private val host: DeviceCapabilityHost, private val expectedUrl: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableMapOf<String, Job>()
    private var generation = 0L
    private var closed = false
    private var script: ScriptHandler? = null
    private var installed = false
    val supported: Boolean
    init {
        val origin = DeviceProtocol.origin(expectedUrl)
        var ready = false
        if (origin != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addWebMessageListener(webView, TRANSPORT, setOf(origin)) { _, message, source, main, proxy ->
                    if (!closed && DeviceProtocol.isTrusted(expectedUrl, source.toString(), webView.url, main) && message.type == WebMessageCompat.TYPE_STRING) {
                        message.data?.let { receive(it, proxy) }
                    }
                }
                installed = true
                val sdk = webView.context.assets.open("device/seed-android.js").bufferedReader().use { it.readText() }
                // The allowed origin also covers subframes; SDK must only exist in the main frame.
                script = WebViewCompat.addDocumentStartJavaScript(webView, "if(window===window.top){$sdk}", setOf(origin))
                ready = true
            } catch (_: Exception) {
                if (installed) WebViewCompat.removeWebMessageListener(webView, TRANSPORT)
                installed = false
            }
        }
        supported = ready
    }
    private fun receive(input: String, proxy: JavaScriptReplyProxy) {
        val id = DeviceProtocol.requestId(input) ?: return
        // A duplicate must never settle the earlier promise with the same correlation ID.
        if (pending.containsKey(id)) return
        val request = try { DeviceProtocol.parse(input) } catch (e: DeviceCapabilityError) { replyError(proxy, id, e.code, e.message ?: "Invalid request"); return }
        if (pending.isNotEmpty()) { replyError(proxy, id, "BUSY", "Another device operation is active"); return }
        val document = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(120000) {
                    if (request.method == "capabilities.list") DeviceProtocol.capabilities() else host.invoke(request.method, request.params)
                }
                if (!closed && generation == document) proxy.postMessage(DeviceProtocol.encode(mapOf("v" to 1, "id" to id, "ok" to true, "result" to result)))
            } catch (_: TimeoutCancellationException) {
                if (!closed && generation == document) replyError(proxy, id, "TIMEOUT", "Device operation timed out")
            } catch (e: CancellationException) { throw e
            } catch (e: DeviceCapabilityError) {
                if (!closed && generation == document) replyError(proxy, id, e.code, e.message ?: "Device operation failed")
            } catch (_: Exception) {
                if (!closed && generation == document) replyError(proxy, id, "INTERNAL_ERROR", "Device operation failed")
            } finally { if (generation == document) pending.remove(id) }
        }
        pending[id] = job
        job.start()
    }
    private fun replyError(proxy: JavaScriptReplyProxy, id: String, code: String, message: String) {
        proxy.postMessage(DeviceProtocol.encode(mapOf("v" to 1, "id" to id, "ok" to false, "error" to mapOf("code" to code, "message" to message))))
    }
    fun onNavigation() {
        generation++
        pending.values.toList().forEach { it.cancel() }
        pending.clear()
    }
    fun close() {
        if (closed) return
        closed = true
        onNavigation()
        script?.remove(); script = null
        if (installed) WebViewCompat.removeWebMessageListener(webView, TRANSPORT)
        installed = false
        scope.cancel()
        host.close()
    }
    companion object { private const val TRANSPORT = "seedDeviceTransport" }
}
