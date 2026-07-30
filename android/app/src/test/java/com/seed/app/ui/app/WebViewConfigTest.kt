package com.seed.app.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WebViewConfig.isAllowedUrl] — the
 * security boundary the App tab's WebView uses to
 * block navigation outside the embedded default and
 * the emulator host alias allowed for host development.
 *
 * These run on the JVM (no Android, no Robolectric)
 * because the predicate is a pure function over a
 * string. The real WebView wiring (config + listener)
 * is tested visually on the emulator in 5.9.
 */
class WebViewConfigTest {

    @Test
    fun `allows http URL on 10_0_2_2 for host development`() {
        assertTrue(WebViewConfig.isAllowedUrl("http://10.0.2.2:7778/"))
    }

    @Test
    fun `allows http URL on 127_0_0_1 as embedded default`() {
        assertTrue(WebViewConfig.isAllowedUrl("http://127.0.0.1:7778/"))
    }

    @Test
    fun `allows https URL on an allowed host`() {
        assertTrue(WebViewConfig.isAllowedUrl("https://127.0.0.1:4443/"))
    }

    @Test
    fun `blocks hosts not in the default allowlist`() {
        assertFalse(WebViewConfig.isAllowedUrl("http://evil.com/"))
        assertFalse(WebViewConfig.isAllowedUrl("https://example.org/"))
    }

    @Test
    fun `blocks javascript scheme`() {
        assertFalse(WebViewConfig.isAllowedUrl("javascript:alert(1)"))
    }

    @Test
    fun `blocks file scheme`() {
        assertFalse(WebViewConfig.isAllowedUrl("file:///etc/passwd"))
    }

    @Test
    fun `blocks data URI scheme`() {
        assertFalse(WebViewConfig.isAllowedUrl("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun `blocks empty and null URLs`() {
        assertFalse(WebViewConfig.isAllowedUrl(""))
        assertFalse(WebViewConfig.isAllowedUrl("   "))
        assertFalse(WebViewConfig.isAllowedUrl(null))
    }

    @Test
    fun `does not match host suffix`() {
        // `10.0.2.2.evil.com` is NOT `10.0.2.2`. The
        // string-contains check would pass; exact host
        // matching must not.
        assertFalse(WebViewConfig.isAllowedUrl("http://10.0.2.2.evil.com/"))
    }

    @Test
    fun `blocks userinfo trickery`() {
        // `http://10.0.2.2@evil.com/` — the userinfo
        // segment is `10.0.2.2`, the real host is
        // `evil.com`. The browser displays `evil.com`;
        // the URL bar shows it; the WebView must treat
        // this as `evil.com` (i.e., blocked).
        assertFalse(WebViewConfig.isAllowedUrl("http://10.0.2.2@evil.com/"))
    }

    @Test
    fun `blocks malformed URLs`() {
        // Unclosed IPv6 bracket — the URI parser throws.
        assertFalse(WebViewConfig.isAllowedUrl("http://[fe80::1"))
    }

    @Test
    fun `honours a custom allowlist`() {
        // If a future config points the WebView at a
        // LAN address (e.g. for testing against a
        // colleague's machine), the predicate must
        // respect that — not silently fall back to the
        // dev default.
        val lanHost = setOf("192.168.1.50")
        assertTrue(WebViewConfig.isAllowedUrl("http://192.168.1.50:7778/", lanHost))
        assertFalse(WebViewConfig.isAllowedUrl("http://10.0.2.2:7778/", lanHost))
    }

    @Test
    fun `scheme check is case-insensitive`() {
        // Some WebView callbacks normalise the scheme;
        // we should not be the layer that breaks.
        assertTrue(WebViewConfig.isAllowedUrl("HTTP://10.0.2.2:7778/"))
        assertFalse(WebViewConfig.isAllowedUrl("JAVA-SCRIPT:alert(1)"))
    }
}
