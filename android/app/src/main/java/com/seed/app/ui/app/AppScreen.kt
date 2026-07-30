package com.seed.app.ui.app

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.seed.app.BuildConfig

/**
 * App tab — shows the user's webapp inside a WebView.
 *
 * Phase 5.3 replaces the Phase 5.2 placeholder with
 * an `AndroidView` wrapping a `WebView` that loads
 * `BuildConfig.WEBAPP_DEV_URL` (the embedded webapp
 * at `127.0.0.1:7778` by default). `10.0.2.2` remains
 * allowed for development against an emulator-hosted
 * webapp.
 *
 * **Security model (defence in depth, two layers):**
 *
 *   1. `network_security_config.xml` only permits
 *      cleartext HTTP to the embedded default
 *      (`127.0.0.1`) and the emulator host alias
 *      (`10.0.2.2`) used for host development. All
 *      other cleartext traffic is blocked at the platform level by
 *      Android 9+.
 *   2. `WebViewConfig.isAllowedUrl` is the second
 *      filter — the `WebViewClient.shouldOverrideUrlLoading`
 *      callback below blocks any navigation to a
 *      host that isn't in the allowlist (including
 *      `javascript:`, `file:`, `data:`, and any
 *      cross-origin surface the user's webapp did
 *      not legitimately produce).
 *
 * If either layer is bypassed (config bug, future
 * change) the other still blocks. The two layers
 * are also tested independently — the URL allowlist
 * is unit-tested on the JVM; the network security
 * config is verified by `assembleDebug` (AAPT
 * confirms it's referenced) and by manual testing
 * on the emulator (Phase 5.9).
 *
 * **Lifecycle:**
 *   - The `WebView` is `remember`-ed so it survives
 *     recomposition. Without this, every recompose
 *     would tear down the WebView and lose scroll
 *     position, form state, etc.
 *   - `DisposableEffect` calls `destroy()` on the
 *     WebView when the composable leaves the
 *     composition. This is essential — the WebView
 *     holds a reference to the Activity Context
 *     and spawns a background renderer thread,
 *     both of which would leak otherwise.
 *
 * **Pull-to-refresh:**
 *   - `SwipeRefreshLayout` (from the platform
 *     `androidx.swiperefreshlayout` library, not
 *     Compose) wraps the WebView. The plan called
 *     this out explicitly: "Pull-to-refresh:
 *     SwipeRefreshLayout (or Compose equivalent)
 *     calls webView.reload()". We use SwipeRefresh
 *     because Compose's `PullToRefreshBox` is only
 *     in material3 1.3+ and our Compose BOM
 *     (2024.06.00) ships material3 1.2.1.
 */
@SuppressLint("SetJavaScriptEnabled") // Safe — the webapp is trusted (we serve it)
@Composable
fun AppScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Stable across recompositions. `remember` (not
    // `rememberSaveable`) — the WebView saves its
    // own state via the parent Activity's
    // onSaveInstanceState.
    val webView = remember { WebView(context) }

    // Tear the WebView down when the composable
    // leaves the composition (tab change, app
    // background, process death). The factory only
    // runs once per AndroidView entry, so the
    // `remember`-ed WebView is the same instance
    // the factory wired up.
    DisposableEffect(Unit) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SwipeRefreshLayout(ctx).apply {
                setOnRefreshListener { webView.reload() }
                addView(
                    webView.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.applySafeSettings()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                // Use the 3-arg overload
                                // (takes a WebResourceRequest)
                                // instead of the deprecated
                                // 2-arg one — the 2-arg
                                // version is on the way out
                                // and only exists for very
                                // old apps. Returning `true`
                                // tells the WebView "you
                                // handle this URL" — and
                                // since we don't call
                                // `view.loadUrl(url)`, the
                                // effective behaviour is
                                // "do nothing", which is
                                // exactly the block we
                                // want. The WebView stays
                                // on the previous page.
                                val url = request.url?.toString()
                                return !WebViewConfig.isAllowedUrl(url)
                            }
                        }
                        loadUrl(BuildConfig.WEBAPP_DEV_URL)
                    },
                )
            }
        },
        // No `update` block: the WebView owns its
        // own state, and re-loading the URL on
        // recomposition would reset the page.
    )
}

/**
 * The project's safe WebView defaults. Pulled out
 * of [AppScreen] so the values are auditable in
 * one place. The strict defaults (no file access,
 * no mixed content) match the security model in
 * the [AppScreen] kdoc; JS + DOM storage are
 * enabled because the user's webapp is allowed
 * to be interactive.
 */
@SuppressLint("SetJavaScriptEnabled") // Safe — see applySafeSettings kdoc
private fun WebSettings.applySafeSettings() {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // cacheMode defaults to LOAD_DEFAULT, which is
    // what we want — the worker's edits to app.py
    // are picked up because Flask is in debug mode
    // (FLASK_DEBUG=1, added in Phase 4.4), and the
    // browser will revalidate on reload.
}
