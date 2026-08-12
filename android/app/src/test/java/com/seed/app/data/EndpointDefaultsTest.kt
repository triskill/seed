package com.seed.app.data

import com.seed.app.BuildConfig
import com.seed.app.ui.app.WebViewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointDefaultsTest {

    @Test
    fun `backend defaults to the embedded runtime`() {
        assertEquals("http://127.0.0.1:7777/", BuildConfig.BACKEND_DEV_URL)
    }

    @Test
    fun `webapp defaults to the embedded runtime`() {
        // Phase 8 carry-over: proot on Android does not
        // implement fork(2), so the Flask subprocess can't
        // be spawned. The lifespan mounts the Flask WSGI
        // app inside the FastAPI process via a2wsgi, so the
        // webapp is reachable on the same port as the backend.
        assertEquals("http://127.0.0.1:7777/", BuildConfig.WEBAPP_DEV_URL)
    }

    @Test
    fun `WebView allows the default webapp endpoint`() {
        assertTrue(WebViewConfig.isAllowedUrl(BuildConfig.WEBAPP_DEV_URL))
    }
}
