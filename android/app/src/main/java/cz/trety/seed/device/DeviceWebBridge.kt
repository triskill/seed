package cz.trety.seed.device

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
    private val owned = mutableSetOf<String>()
    private val tombstones = linkedSetOf<String>()
    private val controls = mutableSetOf<String>()
    private fun rememberClosed(id: String) {
        owned.remove(id)
        tombstones.add(id)
        if (tombstones.size > 64) tombstones.remove(tombstones.first())
    }
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
        DeviceProtocol.streamAck(input)?.let { (subscriptionId, sequence) ->
            if (subscriptionId in owned) (host as? DeviceStreamHost)?.acknowledge(subscriptionId, sequence)
            return
        }
        val id = DeviceProtocol.requestId(input) ?: return
        // A duplicate must never settle the earlier promise with the same correlation ID.
        if (pending.containsKey(id)) return
        val request = try { DeviceProtocol.parse(input) } catch (e: DeviceCapabilityError) { replyError(proxy, id, e.code, e.message ?: "Invalid request"); return }
        val control = request.method == "sensor.unsubscribe"
        if (!control && pending.keys.any { it !in controls }) { replyError(proxy, id, "BUSY", "Another device operation is active"); return }
        if (control) {
            val subscriptionId = request.params["subscriptionId"] as String
            if (subscriptionId !in owned && subscriptionId !in tombstones) { replyError(proxy, id, "PERMISSION_DENIED", "Subscription belongs to another document"); return }
        }
        if (request.method == "sensor.subscribe" && owned.size >= 4) { replyError(proxy, id, "BUSY", "Subscription limit reached"); return }
        val document = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var subscriptionId: String? = null
            try {
                val result = withTimeout(120000) {
                    when (request.method) {
                        "capabilities.list" -> DeviceProtocol.capabilities()
                        "sensor.unsubscribe" -> {
                            val streamId = request.params["subscriptionId"] as String
                            val result = if (streamId in tombstones) mapOf("stopped" to false) else (host as? DeviceStreamHost)?.unsubscribe(streamId) ?: throw DeviceCapabilityError("UNAVAILABLE", "Streaming unavailable")
                            rememberClosed(streamId)
                            result
                        }
                        "sensor.subscribe" -> {
                            val streamHost = host as? DeviceStreamHost ?: throw DeviceCapabilityError("UNAVAILABLE", "Streaming unavailable")
                            val result = streamHost.subscribe(request.params) { data ->
                                if (!closed && generation == document && DeviceProtocol.isTrusted(expectedUrl, expectedUrl, webView.url, true)) {
                                    val streamId = data["subscriptionId"] as? String ?: throw DeviceCapabilityError("INTERNAL_ERROR", "Invalid stream ID")
                                    if (streamId.isEmpty() || streamId.length > 64) throw DeviceCapabilityError("INTERNAL_ERROR", "Invalid stream ID")
                                    subscriptionId = streamId
                                    if (streamId !in tombstones) owned.add(streamId)
                                    if (data["event"] == "closed") rememberClosed(streamId)
                                    try { proxy.postMessage(DeviceProtocol.encode(data + mapOf("v" to 1, "type" to "stream", "id" to id))) }
                                    catch (e: Exception) { rememberClosed(streamId); streamHost.unsubscribe(streamId); throw e }
                                }
                            }
                            val streamId = result["subscriptionId"] as? String ?: throw DeviceCapabilityError("INTERNAL_ERROR", "Invalid stream acknowledgement")
                            if (streamId.isEmpty() || streamId.length > 64) throw DeviceCapabilityError("INTERNAL_ERROR", "Invalid stream ID")
                            subscriptionId = streamId
                            if (closed || generation != document) streamHost.unsubscribe(streamId)
                            else if (streamId !in tombstones) owned.add(streamId)
                            result
                        }
                        else -> host.invoke(request.method, request.params)
                    }
                }
                if (!closed && generation == document) proxy.postMessage(DeviceProtocol.encode(mapOf("v" to 1, "id" to id, "ok" to true, "result" to result)))
            } catch (_: TimeoutCancellationException) {
                subscriptionId?.let { (host as? DeviceStreamHost)?.unsubscribe(it); rememberClosed(it) }
                if (!closed && generation == document) replyError(proxy, id, "TIMEOUT", "Device operation timed out")
            } catch (e: CancellationException) {
                subscriptionId?.let { (host as? DeviceStreamHost)?.unsubscribe(it) }
                // A native lifecycle cancellation can leave the document alive.
                // Complete its Promise instead of waiting for the SDK timeout.
                if (!closed && generation == document) replyError(proxy, id, "CANCELLED", "Device operation cancelled")
                throw e
            } catch (e: DeviceCapabilityError) {
                subscriptionId?.let { (host as? DeviceStreamHost)?.unsubscribe(it); rememberClosed(it) }
                if (!closed && generation == document) replyError(proxy, id, e.code, e.message ?: "Device operation failed")
            } catch (_: Exception) {
                subscriptionId?.let { (host as? DeviceStreamHost)?.unsubscribe(it); rememberClosed(it) }
                if (!closed && generation == document) replyError(proxy, id, "INTERNAL_ERROR", "Device operation failed")
            } finally { if (generation == document) { pending.remove(id); controls.remove(id) } }
        }
        if (control) controls.add(id)
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
        controls.clear()
        (host as? DeviceStreamHost)?.stopStreams("CANCELLED", "Document navigated")
        owned.clear()
        tombstones.clear()
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
