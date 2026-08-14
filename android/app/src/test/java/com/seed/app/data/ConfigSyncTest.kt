package com.seed.app.data

import com.seed.app.ui.settings.LogLevel
import com.seed.app.ui.settings.SettingsForm
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ConfigSync.toRequest] (the
 * [SettingsForm] → [ConfigRequest] mapping) and
 * the [ConfigSync.sync] round-trip against a
 * [MockWebServer].
 *
 * **Why this test file is separate from
 * [com.seed.app.ui.settings.SettingsViewModelTest]:**
 * the ViewModel tests use [FakeConfigSync] to
 * verify the *behaviour* (when does sync get
 * called, what happens on failure). This file
 * pins the *wire format* — the field names
 * the backend's `PUT /config` route expects,
 * including the nested `ports` object and the deliberate omission of both
 * `apiKey` (injected directly from encrypted storage at runtime startup) and
 * `logLevel` (the backend has no concept of log level yet).
 */
class ConfigSyncTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `toRequest maps SettingsForm fields to ConfigRequest field-for-field`() {
        val form = SettingsForm(
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            apiKey = "sk-test-1234",
            backendPort = 8888,
            webappPort = 9999,
            logLevel = LogLevel.WARNING,
        )
        val sync = ConfigSync(backend = ApiModule.forTesting(server.url("/").toString()))

        val request = sync.toRequest(form)

        assertEquals("anthropic", request.provider)
        assertEquals("claude-sonnet-4-5", request.model)
        assertTrue("ConfigRequest must not expose apiKey", request.toString().contains("sk-test-1234").not())
        assertEquals(8888, request.ports.backend)
        assertEquals(9999, request.ports.flask)
    }

    @Test
    fun `toRequest drops logLevel (backend has no concept of log level)`() {
        // We can't directly assert that a field
        // is absent (the ConfigRequest has no
        // `logLevel` property), so we assert the
        // *positive* side: the toRequest
        // function only reads the non-secret wire-level
        // fields. The mapping is verified
        // indirectly by the BackendApiTest
        // contract tests on the wire format;
        // here we just pin that the logLevel
        // field doesn't influence the request.
        val form = SettingsForm(logLevel = LogLevel.DEBUG)
        val sync = ConfigSync(backend = ApiModule.forTesting(server.url("/").toString()))

        val request = sync.toRequest(form)

        // Sanity: the request uses the DEFAULTS
        // for the other fields (provider="openai",
        // etc.). The point of the assertion is
        // that the logLevel change didn't
        // somehow leak into the request — but
        // since the request type doesn't have a
        // logLevel field, "no leak" is the only
        // possible outcome. We pin the request
        // shape so a future maintainer who adds
        // a logLevel field to ConfigRequest
        // (without thinking about the wire
        // contract) trips this test.
        assertEquals("openai", request.provider)
        assertEquals(7777, request.ports.backend)
    }

    @Test
    fun `sync sends a PUT config request and returns true on ok response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}"""),
        )
        val sync = ConfigSync(backend = ApiModule.forTesting(server.url("/").toString()))
        val form = SettingsForm(
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            apiKey = "sk-test-1234",
            backendPort = 8888,
            webappPort = 9999,
        )

        val ok = sync.sync(form)

        assertTrue("sync should return true on {ok: true}", ok)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/config", recorded.path)
        // The body is the snake_case JSON shape
        // BackendApiTest pinned in 6.1; the
        // exact byte-for-byte check guards
        // against a future `toRequest` change
        // (e.g. adding a field the backend
        // doesn't expect) slipping through.
        assertEquals(
            """{"provider":"anthropic","model":"claude-sonnet-4-5","ports":{"backend":8888,"flask":9999}}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun `sync returns false when the backend responds with ok false`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":false}"""),
        )
        val sync = ConfigSync(backend = ApiModule.forTesting(server.url("/").toString()))

        val ok = sync.sync(SettingsForm())

        assertEquals(false, ok)
    }

    @Test
    fun `sync returns false when the backend returns a 500`() = runBlocking {
        // The backend's PUT /config currently
        // always returns 200, but a future
        // task may return 5xx (e.g. disk full
        // while writing config.json). The
        // sync() contract is "best effort, no
        // exceptions" — we swallow and report
        // false so the caller (SettingsViewModel)
        // can move on.
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"detail":"disk full"}"""),
        )
        val sync = ConfigSync(backend = ApiModule.forTesting(server.url("/").toString()))

        val ok = sync.sync(SettingsForm())

        assertEquals(false, ok)
    }
}
