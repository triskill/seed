package com.seed.app.ui.app

import java.net.URI

/**
 * Pure configuration helpers for the App tab's
 * `WebView`. Extracted from [AppScreen] so the
 * security-relevant bits (the URL allowlist) can be
 * unit-tested on the JVM without an emulator.
 *
 * The dev WebView points at the host's Flask webapp
 * (port 7778) via:
 *   - `10.0.2.2` — the emulator's alias for the
 *     host machine's loopback. Works out of the box.
 *   - `127.0.0.1` — used by physical devices that
 *     tunnel the port back with `adb reverse tcp:7778
 *     tcp:7778`.
 *
 * In production (Phase 7+, after the proot runtime
 * lands) the WebView will point at the in-device
 * backend at `http://127.0.0.1:7778/`, so 127.0.0.1
 * stays in the allowlist.
 */
object WebViewConfig {

    /**
     * Hosts the WebView is allowed to navigate to.
     * Everything else is blocked by
     * `WebViewClient.shouldOverrideUrlLoading`.
     */
    val ALLOWED_DEV_HOSTS: Set<String> = setOf("10.0.2.2", "127.0.0.1")

    /**
     * True iff [url] is an `http(s)` URL whose host
     * is in [allowedHosts]. Used to keep the WebView
     * inside the dev network — it cannot be used to
     * load `file://`, `javascript:`, `data:`, or any
     * other cross-origin surface that the user's
     * webapp would not have legitimately produced.
     *
     * The implementation uses [java.net.URI] (not
     * [java.net.URL], and not string matching) so:
     *   - host suffix tricks like `10.0.2.2.evil.com`
     *     don't match `10.0.2.2` (URI splits on `.`,
     *     not on substring containment);
     *   - userinfo tricks like `http://10.0.2.2@evil.com/`
     *     expose the real host (`evil.com`) via
     *     `URI.host`, not the userinfo segment;
     *   - unparseable input is rejected via the
     *     try/catch rather than crashing the WebView.
     */
    fun isAllowedUrl(
        url: String?,
        allowedHosts: Set<String> = ALLOWED_DEV_HOSTS,
    ): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = parsed.host?.lowercase() ?: return false
        return host in allowedHosts
    }
}
