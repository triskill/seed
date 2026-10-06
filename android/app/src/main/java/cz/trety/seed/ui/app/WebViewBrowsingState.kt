package cz.trety.seed.ui.app

import android.os.Bundle
import android.webkit.WebBackForwardList
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** Holds an Activity-bound view only while its AppScreen is composed. No JS heap guarantee. */
internal class WebViewBrowsingState(private var saved: Bundle = Bundle()) {
    private var liveView: WebView? = null
    private var clearRejectedHistory = false

    fun attach(view: WebView) { liveView = view }

    fun release(view: WebView) {
        if (liveView === view) {
            capture(view)
            liveView = null
        }
    }

    // Called by the saveable provider during Activity.onSaveInstanceState, BEFORE disposal.
    fun save(): Bundle {
        liveView?.let(::capture)
        return Bundle(saved)
    }

    private fun capture(view: WebView) {
        val state = Bundle()
        val history = view.saveState(state)
        val urls = history?.urls().orEmpty()
        val trusted = history != null && BrowsingRestorePolicy.isTrustedHistory(urls, history.currentIndex)
        saved = Bundle().apply {
            val current = view.url?.takeIf { WebViewConfig.isAllowedUrl(it) }
            putString(CURRENT, current)
            if (trusted) {
                putBundle(HISTORY, state)
                putStringArrayList(URLS, ArrayList(urls))
                putInt(INDEX, history!!.currentIndex)
            }
        }
    }

    /** Restore before any loadUrl. Unsafe/absent history falls back to a safe current route. */
    fun restoreOrLoad(view: WebView, expectedUrl: String) {
        require(WebViewConfig.isAllowedUrl(expectedUrl)) { "App URL must satisfy WebView navigation policy" }
        val history = saved.getBundle(HISTORY)
        val urls = saved.getStringArrayList(URLS).orEmpty()
        if (history != null && BrowsingRestorePolicy.isTrustedHistory(urls, saved.getInt(INDEX, -1))) {
            val restored = runCatching { view.restoreState(history) }.getOrNull()
            if (restored != null && BrowsingRestorePolicy.isTrustedHistory(restored.urls(), restored.currentIndex)) {
                return // Loading the index here would discard the restored route/history.
            }
            view.stopLoading()
            clearRejectedHistory = true
        }
        view.loadUrl(saved.getString(CURRENT)?.takeIf { WebViewConfig.isAllowedUrl(it) } ?: expectedUrl)
    }

    fun onPageFinished(view: WebView) {
        if (clearRejectedHistory) {
            view.clearHistory()
            clearRejectedHistory = false
        }
    }

    private fun WebBackForwardList.urls(): List<String?> = (0 until size).map { getItemAtIndex(it)?.url }

    companion object {
        private const val HISTORY = "history"
        private const val URLS = "urls"
        private const val INDEX = "index"
        private const val CURRENT = "current"
        val StateSaver = Saver<WebViewBrowsingState, Bundle>(
            save = { it.save() },
            restore = { WebViewBrowsingState(Bundle(it)) },
        )
    }
}

@Composable
internal fun rememberWebViewBrowsingState(): WebViewBrowsingState =
    rememberSaveable(saver = WebViewBrowsingState.StateSaver) { WebViewBrowsingState() }
